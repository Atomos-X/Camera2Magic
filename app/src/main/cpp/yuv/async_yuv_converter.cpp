#include "async_yuv_converter.h"
#include "../utils/log_utils.h"

#define TAG "[AsyncYuv]"

extern jclass g_hook_class;
extern jmethodID g_method_ensureBuffer;
extern jmethodID g_method_getCachedBuffer;
extern jmethodID g_method_onFrameDataUpdated;

static void* worker_thread_entry(void* arg) {
    auto* converter = static_cast<AsyncYuvConverter*>(arg);
    converter->workerLoop();
    return nullptr;
}

bool AsyncYuvConverter::init(EGLDisplay disp, EGLContext main_ctx, EGLConfig cfg, JavaVM* vm) {
    if (running.load()) {
        LOGW(TAG, "Already running, skipping init");
        return false;
    }
    
    running.store(false);
    thread_created = false;

    display = disp;
    jvm = vm;

    const EGLint context_attrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    shared_context = eglCreateContext(disp, cfg, main_ctx, context_attrs);
    if (shared_context == EGL_NO_CONTEXT) {
        LOGE(TAG, "Failed to create shared context, falling back to GLES 2");
        const EGLint context_attrs_2[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
        shared_context = eglCreateContext(disp, cfg, main_ctx, context_attrs_2);
    }

    if (shared_context == EGL_NO_CONTEXT) {
        LOGE(TAG, "Failed to create shared context!");
        return false;
    }

    const EGLint pbuffer_attrs[] = {
        EGL_WIDTH, 1,
        EGL_HEIGHT, 1,
        EGL_NONE
    };
    dummy_surface = eglCreatePbufferSurface(disp, cfg, pbuffer_attrs);
    if (dummy_surface == EGL_NO_SURFACE) {
        LOGE(TAG, "Failed to create dummy surface!");
        eglDestroyContext(disp, shared_context);
        shared_context = EGL_NO_CONTEXT;
        return false;
    }

    running.store(true);
    if (pthread_create(&worker_thread, nullptr, worker_thread_entry, this) != 0) {
        LOGE(TAG, "Failed to create worker thread");
        running.store(false);
        eglDestroyContext(display, shared_context);
        eglDestroySurface(display, dummy_surface);
        shared_context = EGL_NO_CONTEXT;
        dummy_surface = EGL_NO_SURFACE;
        return false;
    }
    thread_created = true;

    LOGI(TAG, "Initialized with shared context");
    return true;
}

void AsyncYuvConverter::destroy() {
    if (!running.load()) return;

    LOGI(TAG, "Shutting down...");

    {
        std::lock_guard<std::mutex> lock(queue_mutex);
        running.store(false);
    }
    cv.notify_all();

    if (thread_created) {
        pthread_join(worker_thread, nullptr);
        thread_created = false;
    }

    if (display != EGL_NO_DISPLAY) {
        if (dummy_surface != EGL_NO_SURFACE) {
            eglDestroySurface(display, dummy_surface);
            dummy_surface = EGL_NO_SURFACE;
        }
        if (shared_context != EGL_NO_CONTEXT) {
            eglDestroyContext(display, shared_context);
            shared_context = EGL_NO_CONTEXT;
        }
    }

    LOGI(TAG, "Destroyed");
}

void AsyncYuvConverter::submitTask(const YuvConversionTask& task) {
    if (!running.load()) {
        LOGW(TAG, "Not running, task dropped");
        return;
    }

    std::lock_guard<std::mutex> lock(queue_mutex);

    if (task_queue.size() >= 3) {
         // LOGW(TAG, "Queue full (%zu), dropping oldest task", task_queue.size());
        task_queue.pop();
    }

    task_queue.push(task);
    cv.notify_one();
}

int AsyncYuvConverter::getQueueSize() {
    std::lock_guard<std::mutex> lock(queue_mutex);
    return task_queue.size();
}

void AsyncYuvConverter::workerLoop() {
    LOGI(TAG, "Worker thread started");

    JNIEnv* env = nullptr;
    if (jvm && jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        LOGE(TAG, "Failed to attach to JVM!");
        return;
    }

    if (!eglMakeCurrent(display, dummy_surface, dummy_surface, shared_context)) {
        LOGE(TAG, "Failed to make context current! Error: 0x%X", eglGetError());
        if (jvm) jvm->DetachCurrentThread();
        return;
    }

    LOGI(TAG, "EGL context bound successfully");

    while (running.load()) {
        YuvConversionTask task;

        {
            std::unique_lock<std::mutex> lock(queue_mutex);
            cv.wait(lock);

            if (!running.load()) break;
            if (task_queue.empty()) continue;

            task = task_queue.front();
            task_queue.pop();
        }

        processTask(task, env);
    }

    yuv_converter.destroy();
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

    if (jvm) {
        jvm->DetachCurrentThread();
    }

    LOGI(TAG, "Worker thread exited");
}

void AsyncYuvConverter::processTask(const YuvConversionTask& task, JNIEnv* env) {

    JNIEnv* callback_env = env;
    int target_width = task.width;
    int target_height = task.height;

    if (task.work_mode == NORMAL) {
        target_width = task.height;
        target_height = task.width;
    }

    if (yuv_converter.getWidth() != target_width || yuv_converter.getHeight() != target_height) {
        LOGI(TAG, "Resizing converter: %dx%d -> %dx%d",
             yuv_converter.getWidth(), yuv_converter.getHeight(),
             target_width, target_height);
        yuv_converter.destroy();
        if (!yuv_converter.init(target_width, target_height)) {
            LOGE(TAG, "Failed to init yuv_converter");
            return;
        }
    }
    
    struct YuvCallback {
        JNIEnv* env;
        int width;
        int height;
        
        void operator()(uint8_t* data, int size) const {
            if (!env || !g_hook_class || !g_method_getCachedBuffer) {
                LOGE(TAG, "JNI Environment error in callback");
                return;
            }

            auto javaBuffer = (jbyteArray)env->CallStaticObjectMethod(
                g_hook_class, g_method_getCachedBuffer);

            if (javaBuffer) {
                env->SetByteArrayRegion(javaBuffer, 0, size,
                                      reinterpret_cast<const jbyte*>(data));
                env->CallStaticVoidMethod(g_hook_class,
                                        g_method_onFrameDataUpdated,
                                        width, height);
                env->DeleteLocalRef(javaBuffer);
            } else {
                LOGE(TAG, "Failed to get Java buffer!");
            }
        }
    };
    
    YuvCallback callback{};
    callback.env = callback_env;
    callback.width = target_width;
    callback.height = target_height;
    
    yuv_converter.process(task.rgba_texture,
                         const_cast<float*>(task.fix_matrix),
                         callback);
}