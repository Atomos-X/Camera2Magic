#include "media_handle.h"
#include "../utils/log_utils.h"
#include <unistd.h>
#include <cerrno>

#define TAG "[MediaHandle]"
#define AVIO_BUFFER_SIZE (1024 * 1024) // 32KB buffer for custom IO


MediaHandle::MediaHandle() {
    init_internal_state();
}

MediaHandle::~MediaHandle() {
    close();
}

// --- Public Methods ---

int MediaHandle::open(const char* source_path) {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (is_opened()) {
        LOGW(TAG, "Media is already open. Closing previous source first.");
        close();
    }
    init_internal_state();

    // avformat_open_input会分配m_format_ctx
    int ret = avformat_open_input(&m_format_ctx, source_path, nullptr, nullptr);
    if (ret < 0) {
        char err_buf[AV_ERROR_MAX_STRING_SIZE] = {0};
        LOGE(TAG, "Failed to open source '%s': %s", source_path, av_make_error_string(err_buf, sizeof(err_buf), ret));
        return ret;
    }

    return post_open_setup();
}

int MediaHandle::open_from_fd(int fd, int64_t offset, int64_t length) {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (is_opened()) {
        close();
    }
    init_internal_state();

    // 复制文件描述符以保证生命周期独立
    m_fd = dup(fd);
    if (m_fd < 0) {
        LOGE(TAG, "Failed to dup file descriptor: %d (%s)", errno, strerror(errno));
        return -1;
    }
    m_fd_offset = offset;
    m_fd_length = length;
    m_fd_pos = 0;

    // 分配AVIO buffer
    m_avio_buffer = static_cast<uint8_t*>(av_malloc(AVIO_BUFFER_SIZE));
    if (!m_avio_buffer) {
        LOGE(TAG, "Failed to allocate AVIO buffer.");
        close(); // 清理dup的fd
        return AVERROR(ENOMEM);
    }

    // 创建自定义AVIOContext
    m_avio_ctx = avio_alloc_context(
            m_avio_buffer,
            AVIO_BUFFER_SIZE,
            0,              // 0表示不可写
            this,           // opaque指针，传给回调函数
            read_packet_callback,
            nullptr,        // write callback
            seek_callback
    );

    if (!m_avio_ctx) {
        LOGE(TAG, "Failed to allocate AVIO context.");
        close();
        return AVERROR(ENOMEM);
    }

    m_format_ctx = avformat_alloc_context();
    m_format_ctx->pb = m_avio_ctx;
    m_format_ctx->flags |= AVFMT_FLAG_CUSTOM_IO;

    // 打开输入流，FFmpeg将使用我们的回调函数进行IO操作
    int ret = avformat_open_input(&m_format_ctx, nullptr, nullptr, nullptr);
    if (ret < 0) {
        char err_buf[AV_ERROR_MAX_STRING_SIZE] = {0};
        LOGE(TAG, "Failed to open input from fd: %s", av_make_error_string(err_buf, sizeof(err_buf), ret));
        close();
        return ret;
    }

    return post_open_setup();
}
void MediaHandle::flush() {
    std::lock_guard<std::mutex> lock(m_mutex);

    // 1. 刷新 AVFormatContext 的 IO 缓冲区
    if (m_format_ctx && m_format_ctx->pb) {
        avio_flush(m_format_ctx->pb);
    }

    // 2. 【关键】刷新 Bitstream Filter
    if (m_bsf_ctx) {
        av_bsf_flush(m_bsf_ctx);
    }
}
void MediaHandle::close() {
    std::lock_guard<std::mutex> lock(m_mutex);

    if (m_bsf_ctx) {
        av_bsf_free(&m_bsf_ctx);
        m_bsf = nullptr;
    }

    if (m_format_ctx) {
        avformat_close_input(&m_format_ctx); // m_format_ctx会被设为NULL
    }

    // 如果是自定义IO，需要手动释放AVIO上下文及其buffer
    if (m_avio_ctx) {
        // avio_alloc_context分配的上下文需要这样释放
        // 不需要单独释放m_avio_buffer，avio_context_free会处理
        av_freep(&m_avio_ctx->buffer);
        avio_context_free(&m_avio_ctx);
    } else if (m_avio_buffer) {
        // 预防性释放，以防avio_alloc_context失败但buffer已分配
        av_freep(&m_avio_buffer);
    }

    if (m_fd != -1) {
        ::close(m_fd);
    }

    init_internal_state(); // 重置所有成员变量
}

void MediaHandle::find_best_streams() {
    if (!is_opened()) return;

    m_video_stream_index = av_find_best_stream(m_format_ctx, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    m_audio_stream_index = av_find_best_stream(m_format_ctx, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);

    if (m_video_stream_index >= 0) {
        LOGI(TAG, "Found video stream at index %d", m_video_stream_index);
    } else {
        LOGW(TAG, "No video stream found.");
    }
    if (m_audio_stream_index >= 0) {
        LOGI(TAG, "Found audio stream at index %d", m_audio_stream_index);
    } else {
        LOGW(TAG, "No audio stream found.");
    }
}

int MediaHandle::read_and_process_packet(AVPacket& packet) {
    if (!is_opened()) {
        return AVERROR_INVALIDDATA;
    }

    // 1. 从容器中读取原始的 packet
    int ret = av_read_frame(m_format_ctx, &packet);
    if (ret < 0) {
        return ret; // 如果是 EOF 或错误，直接返回
    }

    // 2. 如果是视频流并且我们已经初始化了 BSF，就使用它进行转换
    if (packet.stream_index == m_video_stream_index && m_bsf_ctx) {
        // 发送原始 packet 到过滤器
        ret = av_bsf_send_packet(m_bsf_ctx, &packet);
        if (ret < 0) {
            LOGE(TAG, "Failed to send packet to bitstream filter");
            av_packet_unref(&packet); // 发送失败，需要释放原始包
            return ret;
        }

        // 从过滤器接收处理后的 packet(s)
        // 通常是一个输入对应一个输出，但理论上可能改变
        ret = av_bsf_receive_packet(m_bsf_ctx, &packet);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
            // 过滤器需要更多数据或已结束，这在正常情况下不应该在 send 成功后立即发生
            // 返回一个错误或空包信号让调用者重试
            return ret;
        } else if (ret < 0) {
            LOGE(TAG, "Failed to receive packet from bitstream filter");
            return ret;
        }
    }

    return 0; // 成功
}

int MediaHandle::seek(int64_t timestamp_us) {
    if (!is_opened()) {
        return AVERROR_INVALIDDATA;
    }
    std::lock_guard<std::mutex> lock(m_mutex);

    // AVSEEK_FLAG_BACKWARD: 跳转到请求时间戳之前的关键帧
    int ret = av_seek_frame(m_format_ctx, -1, timestamp_us, AVSEEK_FLAG_BACKWARD);

    if (ret < 0) {
        LOGE(TAG, "Seek failed.");
    }

    if (m_bsf_ctx) {
        av_bsf_flush(m_bsf_ctx);
    }

    return ret;
}
AVCodecParameters* MediaHandle::get_video_codec_parameters() const {
    if (m_video_stream_index != -1) {
        return m_format_ctx->streams[m_video_stream_index]->codecpar;
    }
    return nullptr;
}

AVCodecParameters* MediaHandle::get_audio_codec_parameters() const {
    if (m_audio_stream_index != -1) {
        return m_format_ctx->streams[m_audio_stream_index]->codecpar;
    }
    return nullptr;
}

AVStream* MediaHandle::get_video_stream() const {
    if (m_video_stream_index != -1) {
        return m_format_ctx->streams[m_video_stream_index];
    }
    return nullptr;
}

AVStream* MediaHandle::get_audio_stream() const {
    if (m_audio_stream_index != -1) {
        return m_format_ctx->streams[m_audio_stream_index];
    }
    return nullptr;
}


// --- Private Methods ---

void MediaHandle::init_internal_state() {
    m_format_ctx = nullptr;
    m_avio_ctx = nullptr;
    m_avio_buffer = nullptr;
    m_video_stream_index = -1;
    m_audio_stream_index = -1;
    m_fd = -1;
    m_fd_offset = 0;
    m_fd_length = 0;
    m_fd_pos = 0;

    m_bsf = nullptr;
    m_bsf_ctx = nullptr;
}

int MediaHandle::post_open_setup() {
    int ret = avformat_find_stream_info(m_format_ctx, nullptr);
    if (ret < 0) {
        char err_buf[AV_ERROR_MAX_STRING_SIZE] = {0};
        LOGE(TAG, "Failed to find stream info: %s", av_make_error_string(err_buf, sizeof(err_buf), ret));
        close();
        return ret;
    }

    // 打印一些媒体信息用于调试
    av_dump_format(m_format_ctx, 0, m_format_ctx->url, 0);

    find_best_streams();

    if (m_video_stream_index < 0) {
        LOGE(TAG, "Could not find a valid video stream.");
        close();
        return AVERROR_STREAM_NOT_FOUND;
    }

    AVCodecParameters* video_params = get_video_codec_parameters();
    if (video_params) {
        const char* filter_name = nullptr;
        if (video_params->codec_id == AV_CODEC_ID_H264) {
            filter_name = "h264_mp4toannexb";
        } else if (video_params->codec_id == AV_CODEC_ID_HEVC) {
            filter_name = "hevc_mp4toannexb";
        }

        if (filter_name) {
            // 检查容器格式是否真的需要转换。对于MP4/MOV/FLV等是必须的。
            // AVStream 的 extradata 存在是 AVCC 格式的一个强有力信号。
            if (video_params->extradata_size > 0) {
                m_bsf = av_bsf_get_by_name(filter_name);
                if (!m_bsf) {
                    LOGE(TAG, "Failed to find bitstream filter: %s", filter_name);
                    close();
                    return AVERROR_FILTER_NOT_FOUND;
                }

                ret = av_bsf_alloc(m_bsf, &m_bsf_ctx);
                if (ret < 0) {
                    LOGE(TAG, "Failed to allocate bsf context");
                    close();
                    return ret;
                }

                // 从输入流复制编解码器参数到过滤器
                ret = avcodec_parameters_copy(m_bsf_ctx->par_in, video_params);
                if (ret < 0) {
                    LOGE(TAG, "Failed to copy codec params to bsf context");
                    close();
                    return ret;
                }

                ret = av_bsf_init(m_bsf_ctx);
                if (ret < 0) {
                    LOGE(TAG, "Failed to init bsf context");
                    close();
                    return ret;
                }
                LOGI(TAG, "Successfully initialized '%s' bitstream filter.", filter_name);
            } else {
                LOGI(TAG, "No extradata found for H.264/HEVC stream, Annex-B conversion might not be needed.");
            }
        }
    }


    LOGI(TAG, "MediaHandle setup successful.");
    return 0;
}

// --- Custom IO Callbacks (static) ---

int MediaHandle::read_packet_callback(void* opaque, uint8_t* buf, int buf_size) {
    auto* self = static_cast<MediaHandle*>(opaque);
    if (!self || self->m_fd < 0) {
        return AVERROR_INVALIDDATA;
    }

    // 计算可读取的最大字节数
    int64_t remaining_bytes = self->m_fd_length - self->m_fd_pos;
    if (remaining_bytes <= 0) {
        return AVERROR_EOF;
    }
    int bytes_to_read = static_cast<int>(std::min((int64_t)buf_size, remaining_bytes));

    ssize_t bytes_read = ::pread(self->m_fd, buf, bytes_to_read, self->m_fd_offset + self->m_fd_pos);

    if (bytes_read < 0) {
        LOGE(TAG, "Custom IO read failed: %s", strerror(errno));
        return AVERROR(EIO);
    }

    if (bytes_read == 0) {
        return AVERROR_EOF;
    }

    self->m_fd_pos += bytes_read;
    return bytes_read;
}

int64_t MediaHandle::seek_callback(void* opaque, int64_t offset, int whence) {
    auto* self = static_cast<MediaHandle*>(opaque);
    if (!self || self->m_fd < 0) {
        return -1;
    }

    int64_t new_pos = -1;
    switch (whence) {
        case SEEK_SET:
            new_pos = offset;
            break;
        case SEEK_CUR:
            new_pos = self->m_fd_pos + offset;
            break;
        case SEEK_END:
            new_pos = self->m_fd_length + offset;
            break;
        case AVSEEK_SIZE: // FFmpeg想获取文件大小
            return self->m_fd_length;
        default:
            return -1;
    }

    if (new_pos < 0 || new_pos > self->m_fd_length) {
        return -1; // 超出范围
    }

    self->m_fd_pos = new_pos;
    // 返回新的绝对位置
    return self->m_fd_pos;
}

