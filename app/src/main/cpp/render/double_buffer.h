#ifndef CAMERA_MAGIC_DOUBLE_BUFFER_H
#define CAMERA_MAGIC_DOUBLE_BUFFER_H

#include <GLES3/gl3.h>

struct DoubleBufferedTexture {
    GLuint textures[2] = {0, 0};
    GLuint fbos[2] = {0, 0};
    int current_index = 0;
    int width = 0;
    int height = 0;
    bool initialized = false;

    bool init(int w, int h);

    void destroy();

    GLuint getCurrentTexture() const {
        return textures[current_index];
    }

    GLuint getCurrentFBO() const {
        return fbos[current_index];
    }

    GLuint getPreviousTexture() const {
        return textures[1 - current_index];
    }

    GLuint getPreviousFBO() const {
        return fbos[1 - current_index];
    }

    void swap() {
        current_index = 1 - current_index;
    }

    int getWidth() const { return width; }

    int getHeight() const { return height; }


    bool isInitialized() const { return initialized; }
};

#endif // CAMERA_MAGIC_DOUBLE_BUFFER_H