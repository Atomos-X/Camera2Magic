#include "pbo_buffer.h"
#include  "../utils/log_utils.h"

#define TAG "[PBO]"

bool TripleBufferedPBO::init(int w, int h, int num_channels) {
    if (initialized) {
        destroy();
    }

    width = w;
    height = h;
    channels = num_channels;

    switch (channels) {
        case 1:
            gl_format = GL_RED;
            gl_type = GL_UNSIGNED_BYTE;
            buffer_size = w * h;
            break;
        case 2:
            gl_format = GL_RG;
            gl_type = GL_UNSIGNED_BYTE;
            buffer_size = w * h * 2;
            break;
        case 4:
            gl_format = GL_RGBA;
            gl_type = GL_UNSIGNED_BYTE;
            buffer_size = w * h * 4;
            break;
        default:
            LOGE(TAG, "Unsupported channel count: %d. Must be 1, 2, or 4.", channels);
            return false;
    }

    glGenBuffers(3, pbos);
    for (int i = 0; i < 3; i++) {
        glBindBuffer(GL_PIXEL_PACK_BUFFER, pbos[i]);
        glBufferData(GL_PIXEL_PACK_BUFFER, buffer_size, nullptr, GL_STREAM_READ);
        if (glGetError() != GL_NO_ERROR) {
            LOGE(TAG, "Failed to create PBO[%d] with size %zu", i, buffer_size);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            destroy();
            return false;
        }
        fences[i] = 0;
    }
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);

    initialized = true;
    write_index = 0;
    read_index = -1;

    LOGI(TAG, "Initialized: %dx%d, %d channels, 3 PBOs of %.2f MB each",
         w, h, channels, buffer_size / (1024.0 * 1024.0));
    return true;
}

void TripleBufferedPBO::destroy() {
    if (!initialized) return;

    for (int i = 0; i < 3; i++) {
        if (fences[i]) {
            glDeleteSync(fences[i]);
            fences[i] = 0;
        }
    }

    if (pbos[0] != 0) {
        glDeleteBuffers(3, pbos);
        pbos[0] = pbos[1] = pbos[2] = 0;
    }

    initialized = false;
    LOGI(TAG, "Destroyed");
}

void TripleBufferedPBO::startAsyncRead(GLuint fbo) {
    if (!initialized) return;

    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, pbos[write_index]);

    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glPixelStorei(GL_PACK_ROW_LENGTH, 0);
    glReadPixels(0, 0, width, height, gl_format, gl_type, 0);

    if (fences[write_index]) {
        glDeleteSync(fences[write_index]);
    }
    fences[write_index] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);

    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

uint8_t* TripleBufferedPBO::tryMapReadBuffer() {
    if (!initialized || read_index < 0) return nullptr;

    GLsync fence = fences[read_index];
    if (!fence) return nullptr;

    GLenum result = glClientWaitSync(fence, 0, 0);
    if (result != GL_ALREADY_SIGNALED && result != GL_CONDITION_SATISFIED) {
        return nullptr;
    }

    glDeleteSync(fences[read_index]);
    fences[read_index] = 0;

    glBindBuffer(GL_PIXEL_PACK_BUFFER, pbos[read_index]);
    uint8_t* data = (uint8_t*)glMapBufferRange(
        GL_PIXEL_PACK_BUFFER, 0, buffer_size, GL_MAP_READ_BIT);
    
    if (!data) {
        GLenum err = glGetError();
        LOGW(TAG, "glMapBufferRange failed for PBO[%d], GL error: 0x%X", read_index, err);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        return nullptr;
    }

    return data;
}

void TripleBufferedPBO::unmapReadBuffer() {
    if (!initialized || read_index < 0) return;
    
    glBindBuffer(GL_PIXEL_PACK_BUFFER, pbos[read_index]);
    glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
}

void TripleBufferedPBO::advance() {
    if (!initialized) return;
    read_index = write_index;
    write_index = (write_index + 1) % 3;
}
