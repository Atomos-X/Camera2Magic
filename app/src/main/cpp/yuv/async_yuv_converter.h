#ifndef CAMERA_MAGIC_ASYNC_YUV_CONVERTER_H
#define CAMERA_MAGIC_ASYNC_YUV_CONVERTER_H

#include <queue>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <pthread.h>
#include <EGL/egl.h>
#include <jni.h>
#include "yuv_converter.h"
#include "yuv_types.h"

class AsyncYuvConverter {
public:

    bool init(EGLDisplay disp, EGLContext main_ctx, EGLConfig cfg, JavaVM* jvm);

    void destroy();

    void submitTask(const YuvConversionTask& task);

    int getQueueSize();

    void workerLoop();

private:

    pthread_t worker_thread;
    bool thread_created;
    std::queue<YuvConversionTask> task_queue;
    std::mutex queue_mutex;
    std::condition_variable cv;
    std::atomic<bool> running;

    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext shared_context = EGL_NO_CONTEXT;
    EGLSurface dummy_surface = EGL_NO_SURFACE;

    YuvConverter yuv_converter;

    JavaVM* jvm = nullptr;

    void processTask(const YuvConversionTask& task, JNIEnv* env);
};

#endif // CAMERA_MAGIC_ASYNC_YUV_CONVERTER_H