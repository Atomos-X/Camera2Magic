#ifndef CAMERA_MAGIC_PBO_BUFFER_H
#define CAMERA_MAGIC_PBO_BUFFER_H
#include <GLES3/gl3.h>
#include <cstddef>
struct TripleBufferedPBO {
    GLuint pbos[3] = {0, 0, 0};
    GLsync fences[3] = {0, 0, 0};
    int write_index = 0;
    int read_index = -1;
    int width = 0;
    int height = 0;
    size_t buffer_size = 0;
    bool initialized = false;

    int channels = 0;
    GLenum gl_format = GL_NONE;
    GLenum gl_type = GL_NONE;

    bool init(int w, int h, int num_channels);

    void destroy();

    void startAsyncRead(GLuint fbo);

    uint8_t* tryMapReadBuffer();

    void unmapReadBuffer();


    void advance();

    int getWidth() const { return width; }
    int getHeight() const { return height; }
    size_t getBufferSize() const { return buffer_size; }
    bool isInitialized() const { return initialized; }
};

#endif // CAMERA_MAGIC_PBO_BUFFER_H
