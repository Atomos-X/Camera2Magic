#ifndef CAMERA_MAGIC_EGL_CONTEXT_H
#define CAMERA_MAGIC_EGL_CONTEXT_H

#include <EGL/egl.h>
#include <jni.h>

class EGLContextManager {
public:
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    EGLConfig config = nullptr;

    EGLContextManager() = default;

    ~EGLContextManager() = default;

    bool init(JNIEnv* env, jobject surface);

    bool createSharedContext(EGLDisplay shared_display, EGLConfig shared_config, 
                             EGLContext share_context);

    bool makeCurrent();

    void makeNoneCurrent();

    bool swapBuffers();

    void destroy();

    bool isInitialized() const {
        return display != EGL_NO_DISPLAY && context != EGL_NO_CONTEXT;
    }

    bool getVersion(EGLint* major, EGLint* minor);

    int getGLESVersion();
    
private:

    bool chooseConfig(EGLDisplay disp, EGLConfig* cfg);

    EGLContext createContext(EGLDisplay disp, EGLConfig cfg, 
                             EGLContext share_ctx = EGL_NO_CONTEXT);
};

#endif // CAMERA_MAGIC_EGL_CONTEXT_H