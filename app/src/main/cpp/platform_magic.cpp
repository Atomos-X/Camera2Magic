#include "platform_magic.h"
#include "utils/matrix_utils.h"
#include "camera/camera_state.h"
#include "utils/log_utils.h"
#include "utils/magic_config.h"
#define __STDC_CONSTANT_MACROS
extern  "C" {
#include "libavutil/display.h"
}

jclass g_hook_class = nullptr;
static CameraState g_session_state;
static std::mutex g_state_mutex;
static std::mutex g_video_source_mutex;
static bool g_video_source_ready = false;
static int g_video_fd = -1;
static off_t g_video_offset = 0;
static off_t g_video_length = 0;

static int g_api_level = 0;
static std::mutex g_matrix_mutex;
static WorkMode g_current_work_mode = NORMAL;
static JniIds g_jni_ids;

static RenderContext* g_render_context_instance = nullptr;
static std::mutex g_render_thread_mutex;

static const char VS_RGBA_SRC[] =
        "#version 300 es\n"
        "layout(location = 0) in vec4 a_Position;\n"
        "layout(location = 1) in vec2 a_TexCoord;\n"
        "uniform mat4 u_FixMatrix;\n"
        "out vec2 v_TexCoord;\n"
        "void main() {\n"
        "    gl_Position = a_Position;\n"
        "    vec2 calculated_coord =\n"
        "        (u_FixMatrix * vec4(a_TexCoord, 0.0, 1.0)).xy;\n"
        "    v_TexCoord = clamp(calculated_coord, 0.0, 1.0);\n"
        "}\n";

static const char FS_RGBA_SRC[] =
        "#version 300 es\n"
        "precision mediump float;\n"
        "in vec2 v_TexCoord;\n"
        "uniform sampler2D u_Texture;\n"
        "out vec4 o_FragColor;\n"
        "void main() {\n"
        "    o_FragColor = texture(u_Texture, v_TexCoord);\n"
        "}\n";


static float g_external_transform_matrix[16] = {
        1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f};

// [start] Forward Declaration
void video_thread_loop(void *);
void demuxer_thread_loop(RenderContext *ctx);
void audio_thread_loop(RenderContext *ctx);
void stop_renderer();
// [end] Forward Declaration

WorkMode infer_work_mode() {
    int display_ori = g_session_state.display_orientation;
    bool is_front_camera = g_session_state.isFrontCamera();
    WorkMode inferred_mode = NORMAL;
    if (g_session_state.preview_width < g_session_state.preview_height && display_ori == 90) {
        if (is_front_camera) {
            inferred_mode = FACE_RECOGNITION;
        } else {
            inferred_mode = SCAN_QR_CODE;
        }
    }
    return inferred_mode;
}

inline const char* work_mode_to_string(WorkMode mode) {
    switch (mode) {
        case NORMAL:           return "NORMAL";
        case SCAN_QR_CODE:     return "SCAN_QR_CODE";
        case FACE_RECOGNITION: return "FACE_RECOGNITION";
        default:               return "UNKNOWN";
    }
}

static int get_video_rotation(AVStream* stream) {
    if (!stream) return 0;
    size_t size = 0;
    uint8_t* display_matrix = av_stream_get_side_data(stream, AV_PKT_DATA_DISPLAYMATRIX, &size);
    if (display_matrix) {
        double rotation = av_display_rotation_get((int32_t*)display_matrix);
        int angle = (int)rotation;
        angle = (- angle + 360) % 360;
        return angle;
    }
    return 0;
}

static const char* get_mime_type(AVCodecID codec_id) {
    switch (codec_id) {
        case AV_CODEC_ID_H264: return "video/avc";
        case AV_CODEC_ID_HEVC: return "video/hevc";
        case AV_CODEC_ID_AAC:  return "audio/mp4a-latm";
        default: return nullptr;
    }
}

static GLuint compile_shader(GLenum type, const char *shader_src) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &shader_src, nullptr);
    glCompileShader(shader);
    return shader;
}

static GLuint create_program(const char *vs_src, const char *fs_src) {
    GLuint vs = compile_shader(GL_VERTEX_SHADER, vs_src);
    GLuint fs = compile_shader(GL_FRAGMENT_SHADER, fs_src);
    GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    glDeleteShader(vs);
    glDeleteShader(fs);
    return program;
}

static void teardown_gl_pipeline(RenderContext *ctx) {
    if (ctx->oes_program != 0) {
        glDeleteProgram(ctx->oes_program);
        ctx->oes_program = 0;
    }
    if (ctx->rgba_program != 0) {
        glDeleteProgram(ctx->rgba_program);
        ctx->rgba_program = 0;
    }
    if (ctx->oes_texture_id != 0) {
        glDeleteTextures(1, &ctx->oes_texture_id);
        ctx->oes_texture_id = 0;
    }

    ctx->copier.destroy();
    ctx->yuv_converter.destroy();
}

static bool setup_gl_pipeline(RenderContext *ctx) {

    ctx->rgba_program = create_program(VS_RGBA_SRC, FS_RGBA_SRC);
    if (!ctx->rgba_program) return false;

    ctx->rgba_attr_pos = glGetAttribLocation(ctx->rgba_program, "a_Position");
    ctx->rgba_attr_tex_coord = glGetAttribLocation(ctx->rgba_program, "a_TexCoord");
    ctx->rgba_unif_texture = glGetUniformLocation(ctx->rgba_program, "u_Texture");
    ctx->rgba_unif_fix_matrix = glGetUniformLocation(ctx->rgba_program, "u_FixMatrix");

    return true;
}

static void draw_frame_rgba(RenderContext *ctx, GLuint rgba_tex) {
    glUseProgram(ctx->rgba_program);

    int view_w, view_h;
    {
        std::lock_guard<std::mutex> lock(g_state_mutex);
        view_w = g_session_state.preview_width;
        view_h = g_session_state.preview_height;
    }

    glViewport(0, 0, view_w, view_h);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, rgba_tex);
    glUniform1i(ctx->rgba_unif_texture, 0);

    float fix_matrix[16];
    MatrixUtils::previewFixMatrix(fix_matrix, view_w, view_h, ctx->visual_video_w, ctx->visual_video_h,
                                  g_session_state.display_orientation, g_session_state.isFrontCamera(), g_api_level, g_current_work_mode);
    glUniformMatrix4fv(ctx->rgba_unif_fix_matrix, 1, GL_FALSE, fix_matrix);

    const GLfloat vertices[] = {-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f};
    const GLfloat texCoords[] = {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f};

    glVertexAttribPointer(ctx->rgba_attr_pos, 2, GL_FLOAT, GL_FALSE, 0, vertices);
    glEnableVertexAttribArray(ctx->rgba_attr_pos);
    glVertexAttribPointer(ctx->rgba_attr_tex_coord, 2, GL_FLOAT, GL_FALSE, 0, texCoords);
    glEnableVertexAttribArray(ctx->rgba_attr_tex_coord);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

static void teardown_egl_environment(RenderContext *ctx) {
    if (ctx->display != EGL_NO_DISPLAY) {
        eglMakeCurrent(ctx->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (ctx->context != EGL_NO_CONTEXT) eglDestroyContext(ctx->display, ctx->context);
        if (ctx->surface != EGL_NO_SURFACE) eglDestroySurface(ctx->display, ctx->surface);
    }
    ctx->display = EGL_NO_DISPLAY;
    ctx->context = EGL_NO_CONTEXT;
    ctx->surface = EGL_NO_SURFACE;
}

static bool setup_egl_environment(JNIEnv *env, RenderContext *ctx) {
    ANativeWindow *temp_window = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_state_mutex);
        if (!g_session_state.preview_surface) return false;
        temp_window = ANativeWindow_fromSurface(env, g_session_state.preview_surface);
        if (!temp_window) return false;
        ctx->last_known_surface_version = g_session_state.preview_surface_version;
    }
    ctx->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(ctx->display, nullptr, nullptr);
    const EGLint config_attribs[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                                     EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                                     EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
                                     EGL_ALPHA_SIZE, 8,
                                     EGL_NONE};
    EGLint num_configs;
    eglChooseConfig(ctx->display, config_attribs, &ctx->config, 1, &num_configs);
    // GLES 3
    const EGLint context_attrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    ctx->context = eglCreateContext(ctx->display, ctx->config, EGL_NO_CONTEXT, context_attrs);
    if (ctx->context == EGL_NO_CONTEXT) {
        // Fallback to GLES 2
        const EGLint context_attrs_2[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
        ctx->context = eglCreateContext(ctx->display, ctx->config, EGL_NO_CONTEXT, context_attrs_2);
    }
    ctx->surface = eglCreateWindowSurface(ctx->display, ctx->config, temp_window, nullptr);
    ANativeWindow_release(temp_window);
    if (ctx->surface == EGL_NO_SURFACE ||
        !eglMakeCurrent(ctx->display, ctx->surface, ctx->surface, ctx->context))
        return false;
    return true;
}

static void teardown_ffmpeg_pipeline(JNIEnv *env, RenderContext *ctx) {
    if (ctx->video_codec) {
        AMediaCodec_stop(ctx->video_codec);
        AMediaCodec_delete(ctx->video_codec);
        ctx->video_codec = nullptr;
    }
    if (ctx->audio_stream) {
        AAudioStream_requestStop(ctx->audio_stream);
        AAudioStream_close(ctx->audio_stream);
        ctx->audio_stream = nullptr;
    }

    if (ctx->audio_codec) {
        AMediaCodec_stop(ctx->audio_codec);
        AMediaCodec_delete(ctx->audio_codec);
        ctx->audio_codec = nullptr;
    }

    ctx->st_bundle.release(env);

    if (ctx->media_handle) {
        delete ctx->media_handle;
        ctx->media_handle = nullptr;
    }
}

static bool setup_ffmpeg_pipeline(JNIEnv *env, RenderContext *ctx) {
    if (ctx->media_handle) {
        delete ctx->media_handle;
        ctx->media_handle = nullptr;
    }
    ctx->media_handle = new MediaHandle();

    int ret = 0;
    {
        std::lock_guard<std::mutex> lock(g_video_source_mutex);
        ret = ctx->media_handle->open_from_fd(g_video_fd, g_video_offset, g_video_length);
    }
    if (ret < 0) {
        teardown_ffmpeg_pipeline(env, ctx);
        return false;
    }

    ctx->video_track_index = ctx->media_handle->get_video_stream_index();
    ctx->audio_track_index = ctx->media_handle->get_audio_stream_index();

    if (ctx->video_track_index < 0) {
        teardown_ffmpeg_pipeline(env, ctx);
        return false;
    }
    // setup video pipeline
    AVCodecParameters *v_params = ctx->media_handle->get_video_codec_parameters();
    AVStream *v_stream = ctx->media_handle->get_video_stream();
    const char *v_mime = get_mime_type(v_params->codec_id);

    if (v_mime == nullptr) {
        teardown_ffmpeg_pipeline(env, ctx);
        return false;
    }

    int width = v_params->width;
    int height = v_params->height;
    int rotation = get_video_rotation(v_stream);
    LOGW(TAG, "[Video] mime: %s, width: %d, height: %d, rotation: %d",v_mime, width, height, rotation);

    AMediaFormat *v_format = AMediaFormat_new();
    AMediaFormat_setString(v_format, AMEDIAFORMAT_KEY_MIME, v_mime);
    AMediaFormat_setInt32(v_format, AMEDIAFORMAT_KEY_WIDTH, width);
    AMediaFormat_setInt32(v_format, AMEDIAFORMAT_KEY_HEIGHT, height);
    AMediaFormat_setInt32(v_format, AMEDIAFORMAT_KEY_ROTATION, rotation);

    ctx->video_width = width;
    ctx->video_height = height;
    ctx->video_rotation = rotation;
    if (rotation == 90 || rotation == 270) {
        ctx->visual_video_w = height;
        ctx->visual_video_h = width;
    } else {
        ctx->visual_video_w = width;
        ctx->visual_video_h = height;
    }

    int v_size = v_params->extradata_size;
    if (v_size > 0) AMediaFormat_setBuffer(v_format, "csd-0", v_params->extradata, v_size);

    ctx->video_codec = AMediaCodec_createDecoderByType(v_mime);
    if (!ctx->video_codec) {
        AMediaFormat_delete(v_format);
        teardown_ffmpeg_pipeline(env, ctx);
        return false;
    }

    glGenTextures(1, &ctx->oes_texture_id);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, ctx->oes_texture_id);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);

    if (!ctx->st_bundle.init(env, ctx->oes_texture_id)) {
        AMediaFormat_delete(v_format);
        teardown_ffmpeg_pipeline(env, ctx);
        return false;
    }

    ANativeWindow *codec_window = ANativeWindow_fromSurface(env, ctx->st_bundle.java_surface_obj);
    media_status_t v_status = AMediaCodec_configure(ctx->video_codec, v_format, codec_window,
                                                    nullptr, 0);
    ANativeWindow_release(codec_window);
    AMediaFormat_delete(v_format);

    if (v_status != AMEDIA_OK) {
        teardown_ffmpeg_pipeline(env, ctx);
        return false;
    }

    v_status = AMediaCodec_start(ctx->video_codec);
    if (v_status != AMEDIA_OK) {
        teardown_ffmpeg_pipeline(env, ctx);
        return false;
    }

    // audio pipeline
    if (ctx->audio_track_index >= 0) {
        AVCodecParameters *a_params = ctx->media_handle->get_audio_codec_parameters();
        const char *a_mime = get_mime_type(a_params->codec_id);

        if (a_mime) {
            ctx->audio_codec = AMediaCodec_createDecoderByType(a_mime);
            if (ctx->audio_codec) {
                int sample_rate = a_params->sample_rate;
                int channel = a_params->channels;
                AMediaFormat *a_format =  AMediaFormat_new();
                AMediaFormat_setString(a_format, AMEDIAFORMAT_KEY_MIME, a_mime);
                AMediaFormat_setInt32(a_format, AMEDIAFORMAT_KEY_SAMPLE_RATE, sample_rate);
                AMediaFormat_setInt32(a_format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, channel);

                int a_size = a_params->extradata_size;
                if (a_size > 0) AMediaFormat_setBuffer(a_format, "csd-0", a_params->extradata, a_size);

                media_status_t a_status = AMediaCodec_configure(ctx->audio_codec, a_format, nullptr,
                                                                nullptr, 0);
                if (a_status == AMEDIA_OK) {
                    a_status = AMediaCodec_start(ctx->audio_codec);
                    if (a_status == AMEDIA_OK) {
                        AAudioStreamBuilder *builder;
                        AAudio_createStreamBuilder(&builder);

                        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
                        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
                        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
                        AAudioStreamBuilder_setSampleRate(builder, a_params->sample_rate);
                        AAudioStreamBuilder_setChannelCount(builder, a_params->channels);
                        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);

                        aaudio_result_t aaudio_res = AAudioStreamBuilder_openStream(builder, &ctx->audio_stream);
                        if (aaudio_res == AAUDIO_OK) {
                            ctx->audio_channel_count = channel;
                             AAudioStream_requestStop(ctx->audio_stream);
                        }
                        AAudioStreamBuilder_delete(builder);
                    }
                }
                AMediaFormat_delete(a_format);
            }
        }
    }

    ctx->copier.init(ctx->visual_video_w, ctx->visual_video_h);
    ctx->yuv_converter.init(ctx->visual_video_w, ctx->visual_video_h);
    return true;
}

void demuxer_thread_loop(RenderContext *ctx) {
    JNIEnv *env = nullptr;
    if (g_java_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        LOGE(TAG, "Demuxer thread failed to attach to JVM!");
        return;
    }

    if (!ctx->media_handle) {
        g_java_vm->DetachCurrentThread();
        return;
    }

    ctx->media_handle->seek(0);

    AVStream *v_stream = ctx->media_handle->get_video_stream();
    AVStream *a_stream = ctx->media_handle->get_audio_stream();

    AVPacket *pkt = av_packet_alloc();
    int64_t  start_pts = AV_NOPTS_VALUE;

    while (!ctx->abort_request) {
//        size_t v_size = ctx->video_packet_queue.size();
//        size_t a_size = ctx->audio_packet_queue.size();
//
//        bool video_enough = (ctx->video_track_index < 0) || (v_size > 200);
//        bool audio_enough = (ctx->audio_track_index < 0) || (a_size > 200);
//
//        if (video_enough && audio_enough) {
//            std::this_thread::sleep_for(std::chrono::milliseconds(10));
//            continue;
//        }
//        if (v_size > 500 || a_size > 500) {
//            std::this_thread::sleep_for(std::chrono::milliseconds(10));
//            continue;
//        }

        int ret = ctx->media_handle->read_and_process_packet(*pkt);

        if (ret == AVERROR_EOF) {
            if (!ctx->input_eos) {
                if (ctx->video_track_index >= 0) {
                    auto *eos_packet_video = new MediaPacket();
                    eos_packet_video->track_index = ctx->video_track_index;
                    eos_packet_video->flags = AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM;
                    ctx->video_packet_queue.push(eos_packet_video);
                }
                if (ctx->audio_track_index >= 0) {
                    auto *eos_packet_audio = new MediaPacket();
                    eos_packet_audio->track_index = ctx->audio_track_index;
                    eos_packet_audio->flags = AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM;
                    ctx->audio_packet_queue.push(eos_packet_audio);
                }
                ctx->input_eos = true;
            }
            bool video_decoder_done = (ctx->video_track_index < 0) || ctx->video_output_eos;
            bool audio_decoder_done = (ctx->audio_track_index < 0) || ctx->audio_output_eos;
            if (video_decoder_done && audio_decoder_done) {
                LOGW(TAG, "Demuxer thread: EOS");
                if (ctx->video_codec) AMediaCodec_flush(ctx->video_codec);
                if (ctx->audio_codec) {
                    AMediaCodec_flush(ctx->audio_codec);
                    if (ctx->audio_stream) {
                        AAudioStream_requestStop(ctx->audio_stream);
                        AAudioStream_requestFlush(ctx->audio_stream);
                        AAudioStream_requestStart(ctx->audio_stream);
                    }
                }

                ctx->audio_clock.store(0);
                ctx->input_eos = false;
                ctx->video_output_eos = false;
                ctx->audio_output_eos = false;
                ctx->first_key_frame_found.store(false);
                start_pts = AV_NOPTS_VALUE;
                ctx->media_handle->seek(0);

                std::this_thread::sleep_for(std::chrono::milliseconds(50));
            } else std::this_thread::sleep_for(std::chrono::milliseconds(10));
            continue;
        }

        if (ret < 0) {
            char err_buf[128] = {0};
            av_make_error_string(err_buf, 128, ret);
            LOGW(TAG, "FFmpeg read error: %s", err_buf);
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }

        int64_t current_us_pts = 0;
        AVStream *current_stream = nullptr;

        if (pkt->stream_index == ctx->video_track_index) current_stream = v_stream;
        else if (pkt->stream_index == ctx->audio_track_index) current_stream = a_stream;

        if (current_stream) current_us_pts = av_rescale_q(pkt->pts, current_stream->time_base, AV_TIME_BASE_Q);

        if (pkt->stream_index == ctx->video_track_index && start_pts == AV_NOPTS_VALUE) start_pts = current_us_pts;

        if (current_us_pts < start_pts) {
            if (start_pts - current_us_pts > 100000) {
                av_packet_unref(pkt);
                continue;
            }
        }

        int64_t final_pts = current_us_pts - start_pts;
        if (final_pts < 0) final_pts = 0;

        auto *media_pkt = new MediaPacket();
        media_pkt->data.resize(pkt->size);
        memcpy(media_pkt->data.data(), pkt->data, pkt->size);
        media_pkt->pts = final_pts;

        if (pkt->stream_index == ctx->video_track_index) {
            media_pkt->track_index = ctx->video_track_index;
            if (pkt->flags & AV_PKT_FLAG_KEY) media_pkt->flags |= AMEDIACODEC_BUFFER_FLAG_KEY_FRAME;
            ctx->video_packet_queue.push(media_pkt);
        }
        else if (pkt->stream_index == ctx->audio_track_index) {
            media_pkt->track_index = ctx->audio_track_index;
            ctx->audio_packet_queue.push(media_pkt);
        }
        else delete media_pkt;
        av_packet_unref(pkt);
    }
    if (pkt) av_packet_free(&pkt);
    MediaPacket* p;
    while (ctx->video_packet_queue.wait_and_pop_timeout(p, 10)) delete p;
    while (ctx->audio_packet_queue.wait_and_pop_timeout(p, 10)) delete p;

    g_java_vm->DetachCurrentThread();
}

void audio_thread_loop(RenderContext *ctx) {
    JNIEnv *env = nullptr;
    if (g_java_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        LOGE(TAG, "Audio thread failed to attach to JVM!");
        return;
    }

    if (!ctx->audio_codec) {
        LOGE(TAG, "Audio thread: Audio codec not initialized.");
        g_java_vm->DetachCurrentThread();
        return;
    }
    // while (!ctx->abort_request && !ctx->first_key_frame_found) std::this_thread::sleep_for(std::chrono::milliseconds(2));
    if (ctx->audio_codec) {
//        AAudioStream_requestFlush(ctx->audio_stream);
        AAudioStream_requestStart(ctx->audio_stream);
    }
    while (!ctx->abort_request) {
        MediaPacket *packet = nullptr;
        if (ctx->audio_packet_queue.wait_and_pop_timeout(packet, 10)) {
            ssize_t in_buf_idx = AMediaCodec_dequeueInputBuffer(ctx->audio_codec, 0);
            if (in_buf_idx >= 0) {
                size_t buf_size;
                uint8_t *in_buf = AMediaCodec_getInputBuffer(ctx->audio_codec, in_buf_idx, &buf_size);
                if (packet->flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
                    AMediaCodec_queueInputBuffer(ctx->audio_codec, in_buf_idx, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    LOGI(TAG, "Audio thread: Queued Audio EOS to codec.");
                } else if (packet->data.size() <= buf_size) {
                    memcpy(in_buf, packet->data.data(), packet->data.size());
                    AMediaCodec_queueInputBuffer(ctx->audio_codec, in_buf_idx, 0, packet->data.size(), packet->pts, packet->flags);
                } else {
                    LOGE(TAG, "Audio thread: Packet size %zu exceeds input buffer size %zu", packet->data.size(), buf_size);
                }
            }
            delete packet; // Packet consumed
        }

        if (!ctx->audio_output_eos) {
            AMediaCodecBufferInfo info;
            ssize_t out_buf_idx = AMediaCodec_dequeueOutputBuffer(ctx->audio_codec, &info, 0);
            if (out_buf_idx >= 0) {
                if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
                    ctx->audio_output_eos = true;
                    AMediaCodec_releaseOutputBuffer(ctx->audio_codec, out_buf_idx, false);
                } else if (info.size > 0) {
                    size_t buf_size;
                    uint8_t *buffer = AMediaCodec_getOutputBuffer(ctx->audio_codec, out_buf_idx, &buf_size);
                    if (!g_config.play_sound) memset(buffer, 0, info.size);
                    if (ctx->audio_channel_count > 0) {
                        int32_t num_frames = info.size / (ctx->audio_channel_count * static_cast<int32_t>(sizeof(int16_t)));
                        ctx->audio_clock.store(info.presentationTimeUs);
                        AAudioStream_write(ctx->audio_stream, buffer, num_frames, 1000LL * 1000 * 1000);
                    }
                }
                AMediaCodec_releaseOutputBuffer(ctx->audio_codec, out_buf_idx, false);
            }
            /*
            else if (out_buf_idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {}
            else if (out_buf_idx == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {}
            */
        }
    }
    g_java_vm->DetachCurrentThread();
}

void video_thread_loop(void *arg) {
    auto *ctx = static_cast<RenderContext *>(arg);
    JNIEnv *env = nullptr;
    if (g_java_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        return;
    }

    bool pipeline_ready = false;
    bool egl_res = setup_egl_environment(env, ctx);
    bool ffmpeg_res = setup_ffmpeg_pipeline(env, ctx);
    bool gl_res = setup_gl_pipeline(ctx);

    if (egl_res && ffmpeg_res && gl_res) pipeline_ready = true;
    if (!pipeline_ready) {
        g_java_vm->DetachCurrentThread();
        return;
    }

    if (ctx->use_double_buffer) {
        ctx->double_buffer.init(ctx->visual_video_w, ctx->visual_video_h);
        ctx->async_yuv_converter.init(ctx->display,ctx->context,ctx->config,g_java_vm);
    }

    ctx->demuxer_thread = std::thread([ctx](){
        demuxer_thread_loop(ctx);
    });

    if (ctx->audio_track_index >= 0 && ctx->audio_codec) {
        ctx->audio_thread = std::thread([ctx]() {
            audio_thread_loop(ctx);
        });
    }

    while (!ctx->abort_request) {
        MediaPacket *packet = nullptr;
        if (ctx->video_packet_queue.wait_and_pop_timeout(packet, 10)) {
            ssize_t in_buf_idx = AMediaCodec_dequeueInputBuffer(ctx->video_codec, 0);
            if (in_buf_idx >= 0) {
                size_t buf_size;
                uint8_t *in_buf = AMediaCodec_getInputBuffer(ctx->video_codec, in_buf_idx, &buf_size);
                if (packet->flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
                    AMediaCodec_queueInputBuffer(ctx->video_codec, in_buf_idx, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    LOGI(TAG, "Video thread: Queued Video EOS to codec.");
                } else if (packet->data.size() <= buf_size) {
                    memcpy(in_buf, packet->data.data(), packet->data.size());
                    AMediaCodec_queueInputBuffer(ctx->video_codec, in_buf_idx, 0, packet->data.size(), packet->pts, packet->flags);
                } else {
                    LOGE(TAG, "Video/Render thread: Packet size %zu exceeds input buffer size %zu", packet->data.size(), buf_size);
                }
            }
            delete packet;
        }

        bool new_frame_arriver = false;

        if (!ctx->video_output_eos) {
            AMediaCodecBufferInfo info;
            ssize_t out_buf_idx = AMediaCodec_dequeueOutputBuffer(ctx->video_codec, &info, 0);
            if (out_buf_idx >= 0) {
                if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
                    ctx->video_output_eos = true;
                    AMediaCodec_releaseOutputBuffer(ctx->video_codec, out_buf_idx, false);
                } else {
                    int64_t v_pts = info.presentationTimeUs;
                    int64_t a_pts = ctx->audio_clock.load();
                    int64_t diff = v_pts - a_pts;

                    if (!ctx->audio_output_eos && diff > 10000) {
                        if (diff > 40000) diff = 40000;
                        std::this_thread::sleep_for(std::chrono::microseconds(diff));
                    }
                    if (info.size > 0) new_frame_arriver = true;
                    AMediaCodec_releaseOutputBuffer(ctx->video_codec, out_buf_idx, new_frame_arriver);
                }
            } else if (out_buf_idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            }
            else if (out_buf_idx == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
                LOGI(TAG, "Video thread: No output buffer available");
            }
        }

        if (new_frame_arriver) {
            ctx->st_bundle.updateTexImage();
            float st_matrix[16];
            ctx->st_bundle.getTransformMatrix(st_matrix);

            std::string cam_id;
            int preview_width, preview_height, display_orientation;
            int picture_width, picture_height, sensor_orientation;
            {
                std::lock_guard<std::mutex> lock(g_state_mutex);
                cam_id = g_session_state.camera_id;
                preview_width = g_session_state.preview_width;
                preview_height = g_session_state.preview_height;
                display_orientation = g_session_state.display_orientation;
                // picture_width = g_session_state.picture_width; // Keep commented as per original logic
                // picture_height = g_session_state.picture_height; // Keep commented as per original logic
                sensor_orientation = g_session_state.sensor_orientation;
            }

            // ========================================================
            // [双缓冲架构] 核心处理逻辑 (UNCHANGED)
            // ========================================================

            GLuint current_rgba_tex = 0;

            if (ctx->use_double_buffer && ctx->double_buffer.isInitialized()) {
                if (ctx->double_buffer.getWidth() != ctx->visual_video_w ||
                    ctx->double_buffer.getHeight() != ctx->visual_video_h) {

                    ctx->double_buffer.destroy();
                    ctx->double_buffer.init(ctx->visual_video_w, ctx->visual_video_h);
                }

                GLuint current_fbo = ctx->double_buffer.getCurrentFBO();
                current_rgba_tex = ctx->double_buffer.getCurrentTexture();
                ctx->copier.process(ctx->oes_texture_id, st_matrix,ctx->video_rotation, current_fbo);

            } else {
                if (ctx->copier.getWidth() != ctx->visual_video_w ||
                    ctx->copier.getHeight() != ctx->visual_video_h) {
                    ctx->copier.destroy();
                    ctx->copier.init(ctx->visual_video_w, ctx->visual_video_h);
                }
                ctx->copier.process(ctx->oes_texture_id, st_matrix, ctx->video_rotation);
                current_rgba_tex = ctx->copier.getOutputTex();
            }

            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            draw_frame_rgba(ctx, current_rgba_tex);

            EGLBoolean success = eglSwapBuffers(ctx->display, ctx->surface);
            if (!success) break;

            picture_width = preview_width;
            picture_height = preview_height;

            if (g_hook_class && g_method_ensureBuffer) {
                int size = picture_width * picture_height * 3 / 2;
                env->CallStaticVoidMethod(g_hook_class, g_method_ensureBuffer, size);
            }

            float fix_matrix[16];

            MatrixUtils::nv21FixMatrix(fix_matrix, preview_width, preview_height, ctx->visual_video_w, ctx->visual_video_h,
                                       g_session_state.isFrontCamera(), g_current_work_mode);

            if (ctx->use_double_buffer && ctx->double_buffer.isInitialized()) {

                if (ctx->frame_counter > 0) {
                    YuvConversionTask task;
                    task.rgba_texture = ctx->double_buffer.getPreviousTexture();
                    task.width = picture_width;
                    task.height = picture_height;
                    task.work_mode = g_current_work_mode;
                    memcpy(task.fix_matrix, fix_matrix, sizeof(fix_matrix));
//                    task.timestamp_us = info.presentationTimeUs; // Use current info for timestamp
                    task.frame_number = ctx->frame_counter - 1;
                    ctx->async_yuv_converter.submitTask(task);
                }

                ctx->double_buffer.swap();
                ctx->frame_counter++;

            } else {

                if (picture_width != ctx->yuv_converter.width ||
                    picture_height != ctx->yuv_converter.height) {
                    ctx->yuv_converter.destroy();
                    ctx->yuv_converter.init(picture_width, picture_height);
                }

                ctx->yuv_converter.process(current_rgba_tex, fix_matrix,[env, &ctx](uint8_t *data, int size) {
                    if (!env || !g_hook_class || !g_method_getCachedBuffer) return;

                    auto javaBuffer = (jbyteArray) env->CallStaticObjectMethod(g_hook_class, g_method_getCachedBuffer);
                    if (javaBuffer) {
                        env->SetByteArrayRegion(javaBuffer, 0, size,reinterpret_cast<const jbyte *>(data));
                        env->CallStaticVoidMethod(g_hook_class,g_method_onFrameDataUpdated, ctx->yuv_converter.width, ctx->yuv_converter.height);
                        env->DeleteLocalRef(javaBuffer);
                    } else {
                        LOGE(TAG,"FATAL: Failed to allocate Java buffer!");
                    }
                });
            }
        }
    }

    ctx->abort_request.store(true);

    ctx->audio_packet_queue.abort();
    ctx->video_packet_queue.abort();

    if (ctx->demuxer_thread.joinable()) ctx->demuxer_thread.join();
    if (ctx->audio_thread.joinable()) ctx->audio_thread.join();

    if (ctx->double_buffer.isInitialized()) {
        ctx->async_yuv_converter.destroy();
        ctx->double_buffer.destroy();
    }

    teardown_gl_pipeline(ctx);
    teardown_ffmpeg_pipeline(env, ctx);
    teardown_egl_environment(ctx);

    g_java_vm->DetachCurrentThread();
}

void stop_renderer() {
    RenderContext* ctx_to_delete = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_render_thread_mutex);
        if (g_render_context_instance == nullptr) return;
        g_render_context_instance->abort_request.store(true);

        ctx_to_delete = g_render_context_instance;
        g_render_context_instance = nullptr;
    }
    if (ctx_to_delete) {
        if (ctx_to_delete->video_thread.joinable()) ctx_to_delete->video_thread.join();
        delete ctx_to_delete;
    }
}

void start_renderer_if_needed() {
    std::lock_guard<std::mutex> lock(g_render_thread_mutex);
    if (g_render_context_instance != nullptr) return;

    g_render_context_instance = new RenderContext();
    g_render_context_instance->video_thread = std::thread(video_thread_loop, g_render_context_instance);
}

static void print_global_state_unsafe(const char *occasion) {
    LOGW(TAG,
         "+------------ Statue Snapshot on %s ------------+\n"
         " camera_id=%s, sensor_ori=%d, picture_size=%dx%d;\n"
         " surface_ref=%p, display_ori=%d, preview_size=%dx%d;\n"
         "+------------------------------------------------------------+",
         occasion,g_session_state.camera_id.c_str(),g_session_state.sensor_orientation,g_session_state.picture_width,
         g_session_state.picture_height,g_session_state.preview_surface,g_session_state.display_orientation,
         g_session_state.preview_width,g_session_state.preview_height);
    LOGW(TAG, "WorkMode: %s", work_mode_to_string(g_current_work_mode));
}

static void print_global_state_if_changed_unsafe(const char *occasion) {
    bool need_print = false;

    if (g_session_state.last_printed_camera_id != g_session_state.camera_id) {
        need_print = true;
    }

    if (g_session_state.last_printed_preview_surface_version !=
        g_session_state.preview_surface_version) {
        need_print = true;
    }

    if (!need_print) {
        return;
    }

    print_global_state_unsafe(occasion);

    g_session_state.last_printed_camera_id = g_session_state.camera_id;
    g_session_state.last_printed_preview_surface_version =
            g_session_state.preview_surface_version;
}

JNI_FUNC(void)
Java_com_nothing_camera2magic_hook_MagicNative_getApiLevel(JNIEnv *env, jclass, jint apiLevel) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    g_api_level = apiLevel;
}
JNI_FUNC(void)
Java_com_nothing_camera2magic_hook_MagicNative_updateExternalMatrix(JNIEnv *env, jclass, jfloatArray matrixArray) {

    if (!matrixArray) return;
    jfloat *matrix = env->GetFloatArrayElements(matrixArray, nullptr);
    if (!matrix) return;
    {
        std::lock_guard<std::mutex> lock(g_matrix_mutex);
        memcpy(g_external_transform_matrix, matrix, 16 * sizeof(float));

    }
    env->ReleaseFloatArrayElements(matrixArray, matrix, JNI_ABORT);
}

JNI_FUNC(void)
Java_com_nothing_camera2magic_hook_MagicNative_resetVideoSource(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_video_source_mutex);
    if (g_video_fd != -1) {
        close(g_video_fd);
        g_video_fd = -1;
    }
    g_video_offset = 0;
    g_video_length = 0;
    g_video_source_ready = false;
}

JNI_FUNC(jboolean)
Java_com_nothing_camera2magic_hook_MagicNative_processVideo(JNIEnv *env, jclass, jint fd, jlong offset, jlong length) {

    std::lock_guard<std::mutex> lock(g_video_source_mutex);

    AMediaExtractor *temp_extractor = AMediaExtractor_new();
    media_status_t err = AMediaExtractor_setDataSourceFd(temp_extractor, fd, offset, length);

    AMediaExtractor_delete(temp_extractor);

    if (err == AMEDIA_OK) {
        if (g_video_fd != -1) {
            close(g_video_fd);
        }

        g_video_fd = dup(fd);
        g_video_offset = offset;
        g_video_length = length;

        g_video_source_ready = true;

        return JNI_TRUE;
    } else {
        LOGW(TAG, "Failed to set media extractor data source, error: %d", err);
        g_video_source_ready = false;
        return JNI_FALSE;
    }
}

JNI_FUNC(void)
Java_com_nothing_camera2magic_hook_MagicNative_updateCameraParameters(JNIEnv *env, jclass, jstring camera_id, jint sensor_orientation, jint picture_width, jint picture_height) {

    const char *camera_id_str =
            camera_id ? env->GetStringUTFChars(camera_id, nullptr) : "";

    {
        std::lock_guard<std::mutex> lock(g_state_mutex);
        g_session_state.camera_id = camera_id_str;
        g_session_state.sensor_orientation = sensor_orientation;
        g_session_state.picture_width = picture_width;
        g_session_state.picture_height = picture_height;
    }
    if (camera_id) {
        env->ReleaseStringUTFChars(camera_id, camera_id_str);
    }
}

JNI_FUNC(void)
Java_com_nothing_camera2magic_hook_MagicNative_setDisplayOrientation(JNIEnv *env, jclass, jint orientation) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    g_session_state.display_orientation = orientation;
}

JNI_FUNC(void)
Java_com_nothing_camera2magic_hook_MagicNative_registerSurface(JNIEnv *env, jclass, jobject surface) {
    stop_renderer();
    jobject old_surface_ref = nullptr;

    {
        std::lock_guard<std::mutex> lock(g_state_mutex);

        if (g_session_state.preview_surface) {
            old_surface_ref = g_session_state.preview_surface;
            g_session_state.preview_surface = nullptr;
        }

        g_session_state.preview_width = 0;
        g_session_state.preview_height = 0;

        if (surface) {
            g_session_state.preview_surface = env->NewGlobalRef(surface);
            ANativeWindow *window = ANativeWindow_fromSurface(env, g_session_state.preview_surface);
            if (window) {
                g_session_state.preview_width = ANativeWindow_getWidth(window);
                g_session_state.preview_height = ANativeWindow_getHeight(window);
                ANativeWindow_release(window);
            }

            g_current_work_mode = infer_work_mode();
            g_session_state.preview_surface_version++;
            start_renderer_if_needed();
        }
        print_global_state_if_changed_unsafe("registerSurface");
    }

    if (old_surface_ref) {
        env->DeleteGlobalRef(old_surface_ref);
        LOGI(TAG, "Old surface global reference released.");
    }
}

JNI_FUNC(jint) JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_java_vm = vm;
    return JNI_VERSION_1_6;
}

JNI_FUNC(void)
Java_com_nothing_camera2magic_MagicEntry_nativeInit(JNIEnv *env, jobject thiz) {

    if (g_java_hook_object) {
        env->DeleteGlobalRef(g_java_hook_object);
    }
    g_java_hook_object = env->NewGlobalRef(thiz);

    if (g_hook_class) {
        env->DeleteGlobalRef(g_hook_class);
    }

    jclass local_class_ref =
            env->FindClass("com/nothing/camera2magic/hook/MagicNative");
    if (!local_class_ref) {
        LOGE(TAG, "CRITICAL: Failed to find MagicNative class for caching!");
        return;
    }
    g_hook_class = (jclass) env->NewGlobalRef(local_class_ref);
    env->DeleteLocalRef(local_class_ref);

    if (g_hook_class) {
        g_method_getCachedBuffer = env->GetStaticMethodID(g_hook_class, "getCachedBuffer", "()[B");
        g_method_ensureBuffer = env->GetStaticMethodID(g_hook_class, "ensureBuffer", "(I)V");
        g_method_onFrameDataUpdated = env->GetStaticMethodID(g_hook_class, "onFrameDataUpdated", "(II)V");
    }

    jclass  local_config_class = env->FindClass("com/nothing/camera2magic/hook/MagicConfig");
    if (local_config_class == nullptr) return;

    g_jni_ids.magic_config_class = (jclass)env->NewGlobalRef(local_config_class);
    env->DeleteLocalRef(local_config_class);
    if (g_jni_ids.magic_config_class == nullptr) return;
    g_jni_ids.play_sound_fid = env->GetFieldID(g_jni_ids.magic_config_class, "playSound", "Z");
    g_jni_ids.enable_log_fid = env->GetFieldID(g_jni_ids.magic_config_class, "enableLog", "Z");
}

JNI_FUNC(jintArray)
Java_com_nothing_camera2magic_hook_MagicNative_getSurfaceInfo(JNIEnv *env, jclass, jobject surface) {
    jintArray result = env->NewIntArray(3);
    jint temp[3] = {0, 0, 0};

    if (surface) {
        ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
        if (window) {
            temp[0] = ANativeWindow_getWidth(window);
            temp[1] = ANativeWindow_getHeight(window);
            temp[2] = ANativeWindow_getFormat(window);

            // 格式参考 (system/graphics.h):
            // HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED = 1 (通常是预览)
            // HAL_PIXEL_FORMAT_BLOB = 0x21 (33) (通常是 JPEG 拍照)
            // HAL_PIXEL_FORMAT_YCbCr_420_888 = 0x23 (35) (通常是 YUV 数据/拍照)

            ANativeWindow_release(window);
        }
    }

    env->SetIntArrayRegion(result, 0, 3, temp);
    return result;
}
extern "C"
JNIEXPORT void JNICALL
Java_com_nothing_camera2magic_hook_MagicNative_updateNativeConfig(JNIEnv *env, jclass clazz, jobject config_obj) {
    if (config_obj == nullptr) return;
    if (g_jni_ids.magic_config_class == nullptr || g_jni_ids.play_sound_fid == nullptr) return;
    g_config.play_sound = env->GetBooleanField(config_obj, g_jni_ids.play_sound_fid);
    g_config.enable_log = env->GetBooleanField(config_obj, g_jni_ids.enable_log_fid);
}
