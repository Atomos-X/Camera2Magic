#include "double_buffer.h"
#include "../utils/log_utils.h"

#define TAG "[DoubleBuffer]"

bool DoubleBufferedTexture::init(int w, int h) {
    if (initialized) {
        LOGW(TAG, "Already initialized, destroying first");
        destroy();
    }

    width = w;
    height = h;

    for (int i = 0; i < 2; i++) {
        // 创建纹理
        glGenTextures(1, &textures[i]);
        glBindTexture(GL_TEXTURE_2D, textures[i]);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        GLenum error = glGetError();
        if (error != GL_NO_ERROR) {
            LOGE(TAG, "Failed to create texture[%d], error: 0x%X", i, error);
            destroy();
            return false;
        }

        glGenFramebuffers(1, &fbos[i]);
        glBindFramebuffer(GL_FRAMEBUFFER, fbos[i]);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                               GL_TEXTURE_2D, textures[i], 0);

        GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            LOGE(TAG, "FBO[%d] incomplete: 0x%X", i, status);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindTexture(GL_TEXTURE_2D, 0);
            destroy();
            return false;
        }
    }

    glBindTexture(GL_TEXTURE_2D, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    initialized = true;
    current_index = 0;

    LOGI(TAG, "Initialized: %dx%d, Tex[0]=%u, Tex[1]=%u, FBO[0]=%u, FBO[1]=%u",
         w, h, textures[0], textures[1], fbos[0], fbos[1]);
    return true;
}

void DoubleBufferedTexture::destroy() {
    if (!initialized) return;

    if (textures[0] != 0 || textures[1] != 0) {
        glDeleteTextures(2, textures);
        textures[0] = textures[1] = 0;
    }

    if (fbos[0] != 0 || fbos[1] != 0) {
        glDeleteFramebuffers(2, fbos);
        fbos[0] = fbos[1] = 0;
    }

    width = 0;
    height = 0;
    current_index = 0;
    initialized = false;

    LOGI(TAG, "Destroyed");
}