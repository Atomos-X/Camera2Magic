#ifndef CAMERA2_MAGIC_MEDIA_HANDLE_H
#define CAMERA2_MAGIC_MEDIA_HANDLE_H

#include <string>
#include <vector>
#include <mutex>
#include <functional>


// 必须在使用FFmpeg头文件之前定义这个宏
#define __STDC_CONSTANT_MACROS

extern "C" {
#include <libavformat/avformat.h>
#include "libavcodec/bsf.h"
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
}

// 定义一个简单的流信息结构体，方便上层查询
struct StreamInfo {
    int index = -1;
    AVMediaType type = AVMEDIA_TYPE_UNKNOWN;
    AVCodecParameters* codec_params = nullptr;
};

class MediaHandle {
public:
    MediaHandle();
    ~MediaHandle();

    // 禁止拷贝和移动，确保资源管理的唯一性
    MediaHandle(const MediaHandle&) = delete;
    MediaHandle& operator=(const MediaHandle&) = delete;
    MediaHandle(MediaHandle&&) = delete;
    MediaHandle& operator=(MediaHandle&&) = delete;

    /**
     * @brief 打开媒体源 (文件路径或URL)
     * @param source_path 文件路径或RTSP/HTTP等URL
     * @return 0表示成功，负值表示失败
     */
    int open(const char* source_path);

    /**
     * @brief 使用自定义的IO上下文打开媒体源。适用于从文件描述符(fd)读取。
     * @param fd 文件描述符
     * @param offset 起始偏移
     * @param length 数据长度
     * @return 0表示成功，负值表示失败
     */
    int open_from_fd(int fd, int64_t offset, int64_t length);

    /**
     * @brief 关闭媒体源，释放所有相关资源
     */
    void close();

    /**
     * @brief 查找最佳的视频流和音频流
     */
    void find_best_streams();

    /**
     * @brief 读取一个媒体数据包
     * @param packet [out] 读取到的AVPacket的引用
     * @return 0表示成功, AVERROR_EOF表示文件结束, 其他负值表示错误
     */
    int read_and_process_packet(AVPacket& packet);

    void get_video_extradata(uint8_t** out_data, int* out_size);
    void get_audio_extradata(uint8_t** out_data, int* out_size);

    /**
     * @brief 跳转到指定的时间戳 (单位: 微秒)
     * @param timestamp_us 时间戳
     * @return 0表示成功，负值表示失败
     */
    int seek(int64_t timestamp_us);

    // --- Getters ---
    bool is_opened() const { return m_format_ctx != nullptr; }
    int get_video_stream_index() const { return m_video_stream_index; }
    int get_audio_stream_index() const { return m_audio_stream_index; }

    AVCodecParameters* get_video_codec_parameters() const;
    AVCodecParameters* get_audio_codec_parameters() const;
    AVStream* get_video_stream() const;
    AVStream* get_audio_stream() const;

private:
    /**
     * @brief 初始化内部状态，清理残留数据
     */
    void init_internal_state();
    void flush();
    /**
     * @brief 打开媒体后的通用设置步骤
     * @return 0表示成功, 负值表示失败
     */
    int post_open_setup();

    // --- 自定义IO相关 ---
    static int read_packet_callback(void* opaque, uint8_t* buf, int buf_size);
    static int64_t seek_callback(void* opaque, int64_t offset, int whence);

    // 核心FFmpeg上下文
    AVFormatContext* m_format_ctx = nullptr;

    // 自定义IO相关成员
    AVIOContext* m_avio_ctx = nullptr;
    uint8_t* m_avio_buffer = nullptr;
    int m_fd = -1;              // 文件描述符
    int64_t m_fd_offset = 0;    // 起始偏移
    int64_t m_fd_length = 0;    // 总长度
    int64_t m_fd_pos = 0;       // 当前读写位置

    // 流信息
    int m_video_stream_index = -1;
    int m_audio_stream_index = -1;

    const AVBitStreamFilter* m_bsf = nullptr;   // 比特流过滤器 (e.g., h264_mp4toannexb)
    AVBSFContext* m_bsf_ctx = nullptr;          // 比特流过滤器上下文

    // 线程安全锁
    std::mutex m_mutex;
};

#endif //CAMERA2_MAGIC_MEDIA_HANDLE_H
