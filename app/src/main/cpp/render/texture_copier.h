#ifndef CAMERA_MAGIC_TEXTURE_COPIER_H
#define CAMERA_MAGIC_TEXTURE_COPIER_H

#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>

struct TextureCopier {
    GLuint fbo = 0;
    GLuint output_tex = 0;
    GLuint program = 0;
    int width = 0;
    int height = 0;
    GLint u_oes_tex_loc = -1;
    GLint u_mvp_loc = -1;
    GLint u_tex_matrix_loc = -1;

    bool init(int w, int h);

    void destroy();

    void process(GLuint oes_tex_id, float* st_matrix, int video_rotation, GLuint target_fbo = 0);

    GLuint getOutputTex() const { return output_tex; }

    int getWidth() const { return width; }

    int getHeight() const { return height; }

private:

    GLuint loadShader(GLenum type, const char* source);

    static const char* getVertexShader();

    static const char* getFragmentShader();
};

#endif // CAMERA_MAGIC_TEXTURE_COPIER_H