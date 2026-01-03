#ifndef CAMERA_MAGIC_SURFACE_TEXTURE_H
#define CAMERA_MAGIC_SURFACE_TEXTURE_H

#include <jni.h>
#include <android/surface_texture.h>
#include <android/surface_texture_jni.h>
#include <GLES3/gl3.h>

struct SurfaceTextureBundle {
    jobject java_surface_texture_obj = nullptr;
    jobject java_surface_obj = nullptr;
    ASurfaceTexture* native_st = nullptr;

    bool init(JNIEnv* env, GLuint tex_id);

    void getTransformMatrix(float* mtx) const;

    void updateTexImage() const;

    int64_t getTimestamp() const;

    void release(JNIEnv* env);

    jobject getSurface() const { return java_surface_obj; }

    bool isInitialized() const { return native_st != nullptr; }
};

#endif // CAMERA_MAGIC_SURFACE_TEXTURE_H