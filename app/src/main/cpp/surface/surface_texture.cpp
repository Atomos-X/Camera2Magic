#include "surface_texture.h"
#include "../utils/log_utils.h"
#include <limits>

#define TAG "[SurfaceTexture]"

bool SurfaceTextureBundle::init(JNIEnv* env, GLuint tex_id) {

    jclass st_clazz = env->FindClass("android/graphics/SurfaceTexture");
    if (!st_clazz) {
        LOGE(TAG, "Failed to find SurfaceTexture class");
        return false;
    }

    jmethodID st_ctor = env->GetMethodID(st_clazz, "<init>", "(I)V");
    if (!st_ctor) {
        LOGE(TAG, "Failed to find SurfaceTexture constructor");
        return false;
    }
    if(tex_id > std::numeric_limits<jint>::max()) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "OpenGL texture ID exceeds INT_MAX");
    }
    jint safe_tex_id = static_cast<jint>(tex_id);

    jobject st_local = env->NewObject(st_clazz, st_ctor, safe_tex_id);
    if (!st_local) {
        LOGE(TAG, "Failed to create SurfaceTexture object");
        return false;
    }

    java_surface_texture_obj = env->NewGlobalRef(st_local);
    env->DeleteLocalRef(st_local);

    native_st = ASurfaceTexture_fromSurfaceTexture(env, java_surface_texture_obj);
    if (!native_st) {
        LOGE(TAG, "Failed to get ASurfaceTexture handle (requires API 28+)");
        env->DeleteGlobalRef(java_surface_texture_obj);
        java_surface_texture_obj = nullptr;
        return false;
    }

    jclass s_clazz = env->FindClass("android/view/Surface");
    if (!s_clazz) {
        LOGE(TAG, "Failed to find Surface class");
        ASurfaceTexture_release(native_st);
        native_st = nullptr;
        env->DeleteGlobalRef(java_surface_texture_obj);
        java_surface_texture_obj = nullptr;
        return false;
    }

    jmethodID s_ctor = env->GetMethodID(s_clazz, "<init>",
                                        "(Landroid/graphics/SurfaceTexture;)V");
    if (!s_ctor) {
        LOGE(TAG, "Failed to find Surface constructor");
        ASurfaceTexture_release(native_st);
        native_st = nullptr;
        env->DeleteGlobalRef(java_surface_texture_obj);
        java_surface_texture_obj = nullptr;
        return false;
    }

    jobject s_local = env->NewObject(s_clazz, s_ctor, java_surface_texture_obj);
    if (!s_local) {
        LOGE(TAG, "Failed to create Surface object");
        ASurfaceTexture_release(native_st);
        native_st = nullptr;
        env->DeleteGlobalRef(java_surface_texture_obj);
        java_surface_texture_obj = nullptr;
        return false;
    }

    java_surface_obj = env->NewGlobalRef(s_local);
    env->DeleteLocalRef(s_local);

    LOGI(TAG, "Initialized SurfaceTexture with texture ID: %d", tex_id);
    return true;
}

void SurfaceTextureBundle::getTransformMatrix(float* mtx) const {
    if (native_st) {
        ASurfaceTexture_getTransformMatrix(native_st, mtx);
    }
}

void SurfaceTextureBundle::updateTexImage() const {
    if (native_st) {
        ASurfaceTexture_updateTexImage(native_st);
    }
}

int64_t SurfaceTextureBundle::getTimestamp() const {
    if (native_st) {
        return ASurfaceTexture_getTimestamp(native_st);
    }
    return 0;
}

void SurfaceTextureBundle::release(JNIEnv* env) {

    if (native_st) {
        ASurfaceTexture_release(native_st);
        native_st = nullptr;
    }

    if (java_surface_obj) {
        env->DeleteGlobalRef(java_surface_obj);
        java_surface_obj = nullptr;
    }

    if (java_surface_texture_obj) {

        jclass st_clazz = env->FindClass("android/graphics/SurfaceTexture");
        if (st_clazz) {
            jmethodID release_mid = env->GetMethodID(st_clazz, "release", "()V");
            if (release_mid) {
                env->CallVoidMethod(java_surface_texture_obj, release_mid);
            }
        }

        env->DeleteGlobalRef(java_surface_texture_obj);
        java_surface_texture_obj = nullptr;
    }

    LOGI(TAG, "Released SurfaceTexture");
}