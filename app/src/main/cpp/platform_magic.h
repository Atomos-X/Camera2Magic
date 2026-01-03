#ifndef CAMERA2MAGIC_PLATFORM_MAGIC_H
#define CAMERA2MAGIC_PLATFORM_MAGIC_H

#include <jni.h>
#include <string>
#include <chrono>
#include <mutex>
#include <pthread.h>
#include <thread>
#include <queue>
#include <condition_variable>
#include <atomic>
#include <unistd.h>
#include <cmath>
#include <cerrno>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <android/surface_texture.h>
#include <android/surface_texture_jni.h>

#include "render/texture_copier.h"
#include "render/double_buffer.h"
#include "yuv/async_yuv_converter.h"
#include "surface/surface_texture.h"
#include "utils/work_mode.h"
#include "thread_safe_queue.h"
#include "media/media_handle.h"


#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define TAG "[Platform]"

#define JNI_FUNC(ret_type) extern "C" __attribute__((visibility("default"))) JNIEXPORT ret_type JNICALL

struct JniIds {
    jclass magic_config_class;
    jfieldID play_sound_fid;
    jfieldID enable_log_fid;

    JniIds() : magic_config_class(nullptr),
               play_sound_fid(nullptr),
               enable_log_fid(nullptr) {};
};

struct MediaPacket {
    ssize_t track_index = -1;
    std::vector<uint8_t> data;
    int64_t pts = 0;
    uint32_t flags = 0;
};

struct RenderContext {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    EGLConfig config = nullptr;

    TextureCopier copier;
    DoubleBufferedTexture double_buffer;
    AsyncYuvConverter async_yuv_converter;
    bool use_double_buffer = true;
    int frame_counter = 0;

    ANativeWindow *native_window = nullptr;
    int last_known_surface_version = -1;

    GLuint oes_program = 0;
    GLuint rgba_program = 0;
    GLint rgba_attr_pos = -1;
    GLint rgba_attr_tex_coord = -1;
    GLint rgba_unif_texture = -1;
    GLint rgba_unif_fix_matrix = -1;
    GLuint oes_texture_id = 0;

    SurfaceTextureBundle st_bundle;
    YuvConverter yuv_converter;

    AMediaExtractor *extractor = nullptr;
    MediaHandle *media_handle = nullptr;
    AMediaCodec *video_codec = nullptr;
    AMediaCodec *audio_codec = nullptr;

    AAudioStream *audio_stream = nullptr;
    int32_t video_track_index = -1;
    int32_t audio_track_index = -1;
    int32_t audio_channel_count = 0;

    bool input_eos = false;
    bool video_output_eos = false;
    bool audio_output_eos = false;
    int extractor_fd = -1;
    int32_t video_width = 0;
    int32_t video_height = 0;
    int32_t visual_video_w = 0;
    int32_t visual_video_h = 0;
    int32_t video_rotation = 0;

    // For multi-threading
    std::thread demuxer_thread;
    std::thread video_thread;
    std::thread audio_thread;
    ThreadSafeQueue<MediaPacket*> video_packet_queue;
    ThreadSafeQueue<MediaPacket*> audio_packet_queue;

    std::atomic<bool> abort_request = {false};
    std::atomic<bool> first_key_frame_found = { false };


    std::atomic<int64_t> audio_clock = {0};
};

JavaVM *g_java_vm = nullptr;
static jobject g_java_hook_object = nullptr;
jmethodID g_method_ensureBuffer = nullptr;
jmethodID g_method_getCachedBuffer = nullptr;
jmethodID g_method_onFrameDataUpdated = nullptr;

#endif //CAMERA2MAGIC_PLATFORM_MAGIC_H
