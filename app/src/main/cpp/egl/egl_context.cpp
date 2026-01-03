#include "egl_context.h"
#include "../utils/log_utils.h"
#include <android/native_window_jni.h>
#include <GLES3/gl3.h>
#include <cstring>

#define TAG "[EGLContext]"

bool EGLContextManager::init(JNIEnv* env, jobject surface) {
    if (isInitialized()) {
        LOGW(TAG, "EGL already initialized, destroying first");
        destroy();
    }

    ANativeWindow* native_window = ANativeWindow_fromSurface(env, surface);
    if (!native_window) {
        LOGE(TAG, "Failed to get ANativeWindow from Surface");
        return false;
    }

    display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY) {
        LOGE(TAG, "Failed to get EGL display");
        ANativeWindow_release(native_window);
        return false;
    }

    EGLint major, minor;
    if (!eglInitialize(display, &major, &minor)) {
        LOGE(TAG, "Failed to initialize EGL");
        display = EGL_NO_DISPLAY;
        ANativeWindow_release(native_window);
        return false;
    }

    LOGI(TAG, "EGL version: %d.%d", major, minor);

    if (!chooseConfig(display, &config)) {
        LOGE(TAG, "Failed to choose EGL config");
        eglTerminate(display);
        display = EGL_NO_DISPLAY;
        ANativeWindow_release(native_window);
        return false;
    }

    context = createContext(display, config);
    if (context == EGL_NO_CONTEXT) {
        LOGE(TAG, "Failed to create EGL context");
        eglTerminate(display);
        display = EGL_NO_DISPLAY;
        ANativeWindow_release(native_window);
        return false;
    }

    this->surface = eglCreateWindowSurface(display, config, native_window, nullptr);
    ANativeWindow_release(native_window);

    if (this->surface == EGL_NO_SURFACE) {
        LOGE(TAG, "Failed to create EGL window surface");
        eglDestroyContext(display, context);
        eglTerminate(display);
        context = EGL_NO_CONTEXT;
        display = EGL_NO_DISPLAY;
        return false;
    }

    if (!makeCurrent()) {
        LOGE(TAG, "Failed to make EGL context current");
        destroy();
        return false;
    }

    LOGI(TAG, "EGL context initialized successfully (GLES %d)", getGLESVersion());
    return true;
}

bool EGLContextManager::createSharedContext(EGLDisplay shared_display, 
                                             EGLConfig shared_config, 
                                             EGLContext share_context) {
    if (isInitialized()) {
        LOGW(TAG, "Context already exists, destroying first");
        destroy();
    }

    display = shared_display;
    config = shared_config;

    context = createContext(display, config, share_context);
    if (context == EGL_NO_CONTEXT) {
        LOGE(TAG, "Failed to create shared EGL context");
        display = EGL_NO_DISPLAY;
        return false;
    }

    const EGLint pbuffer_attribs[] = {
        EGL_WIDTH, 1,
        EGL_HEIGHT, 1,
        EGL_NONE
    };
    
    surface = eglCreatePbufferSurface(display, config, pbuffer_attribs);
    if (this->surface == EGL_NO_SURFACE) {
        LOGE(TAG, "Failed to create PBuffer surface");
        eglDestroyContext(display, context);
        context = EGL_NO_CONTEXT;
        display = EGL_NO_DISPLAY;
        return false;
    }

    LOGI(TAG, "Shared EGL context created successfully");
    return true;
}

bool EGLContextManager::makeCurrent() {
    if (!isInitialized() || surface == EGL_NO_SURFACE) {
        LOGE(TAG, "Cannot make current: not initialized or no surface");
        return false;
    }

    if (!eglMakeCurrent(display, surface, surface, context)) {
        LOGE(TAG, "eglMakeCurrent failed: 0x%X", eglGetError());
        return false;
    }

    return true;
}

void EGLContextManager::makeNoneCurrent() {
    if (display != EGL_NO_DISPLAY) {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
}

bool EGLContextManager::swapBuffers() {
    if (!isInitialized() || surface == EGL_NO_SURFACE) {
        return false;
    }

    return eglSwapBuffers(display, surface) == EGL_TRUE;
}

void EGLContextManager::destroy() {
    if (display != EGL_NO_DISPLAY) {
        makeNoneCurrent();

        if (context != EGL_NO_CONTEXT) {
            eglDestroyContext(display, context);
            context = EGL_NO_CONTEXT;
        }

        if (surface != EGL_NO_SURFACE) {
            eglDestroySurface(display, surface);
            surface = EGL_NO_SURFACE;
        }

        display = EGL_NO_DISPLAY;
    }

    config = nullptr;
    LOGI(TAG, "EGL context destroyed");
}

bool EGLContextManager::getVersion(EGLint* major, EGLint* minor) {
    if (display == EGL_NO_DISPLAY) {
        return false;
    }

    return eglInitialize(display, major, minor) == EGL_TRUE;
}

int EGLContextManager::getGLESVersion() {
    if (!isInitialized()) {
        return 0;
    }

    const char* version = (const char*)glGetString(GL_VERSION);
    if (!version) {
        return 0;
    }

    // 解析版本号："OpenGL ES X.Y ..."
    if (strstr(version, "OpenGL ES 3")) {
        return 3;
    } else if (strstr(version, "OpenGL ES 2")) {
        return 2;
    }

    return 0;
}

bool EGLContextManager::chooseConfig(EGLDisplay disp, EGLConfig* cfg) {
    // 优先尝试 GLES3 配置
    const EGLint config_attribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 0,
        EGL_STENCIL_SIZE, 0,
        EGL_NONE
    };

    EGLint num_configs = 0;
    if (eglChooseConfig(disp, config_attribs, cfg, 1, &num_configs) && num_configs > 0) {
        LOGI(TAG, "Using GLES3 compatible config");
        return true;
    }

    // 回退到 GLES2 配置
    LOGW(TAG, "GLES3 config not available, falling back to GLES2");
    const EGLint config_attribs_gles2[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 0,
        EGL_STENCIL_SIZE, 0,
        EGL_NONE
    };

    if (eglChooseConfig(disp, config_attribs_gles2, cfg, 1, &num_configs) && num_configs > 0) {
        LOGI(TAG, "Using GLES2 compatible config");
        return true;
    }

    LOGE(TAG, "Failed to find any suitable EGL config");
    return false;
}

EGLContext EGLContextManager::createContext(EGLDisplay disp, EGLConfig cfg, 
                                             EGLContext share_ctx) {
    // 尝试创建 GLES3 上下文
    const EGLint context_attrs_gles3[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };

    EGLContext ctx = eglCreateContext(disp, cfg, share_ctx, context_attrs_gles3);
    if (ctx != EGL_NO_CONTEXT) {
        LOGI(TAG, "Created GLES3 context");
        return ctx;
    }

    // 回退到 GLES2
    LOGW(TAG, "Failed to create GLES3 context, falling back to GLES2");
    const EGLint context_attrs_gles2[] = {
        EGL_CONTEXT_CLIENT_VERSION, 2,
        EGL_NONE
    };

    ctx = eglCreateContext(disp, cfg, share_ctx, context_attrs_gles2);
    if (ctx != EGL_NO_CONTEXT) {
        LOGI(TAG, "Created GLES2 context");
        return ctx;
    }

    LOGE(TAG, "Failed to create any EGL context: 0x%X", eglGetError());
    return EGL_NO_CONTEXT;
}