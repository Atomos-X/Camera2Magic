#ifndef CAMERA_MAGIC_YUV_CONVERTER_H
#define CAMERA_MAGIC_YUV_CONVERTER_H

#include <functional>
#include <vector>
#include <GLES3/gl3.h>
#include "../buffer/pbo_buffer.h"

/**
 *
 * It uses two separate render passes:
 * 1.  A "Y pass" that renders the luminance (Y) component into a single-channel FBO.
 * 2.  A "UV pass" that renders the chrominance (UV) components into a two-channel FBO
 *     at half resolution.
 */
struct YuvConverter {
    int width = 0;
    int height = 0;
    bool use_pbo = true;

    GLuint vertex_shader = 0;

    // Y-plane resources
    GLuint y_fbo = 0;
    GLuint y_texture = 0;
    GLuint y_program = 0;
    GLint y_u_tex_loc = -1;
    GLint y_u_fix_matrix_loc = -1;
    TripleBufferedPBO pbo_y;

    // UV-plane resources
    GLuint uv_fbo = 0;
    GLuint uv_texture = 0;
    GLuint uv_program = 0;
    GLint uv_u_tex_loc = -1;
    GLint uv_u_fix_matrix_loc = -1;
    TripleBufferedPBO pbo_uv;

    // Combined buffer for final NV21 data
    std::vector<uint8_t> nv21_buffer;

    bool init(int target_width, int target_height, bool enable_pbo = true);

    /**
     * Destroys all allocated OpenGL resources.
     */
    void destroy();


    template<typename Func>
    void process(GLuint rgba_tex, float* fix_matrix, Func data_callback);

    int getWidth() const { return width; }
    int getHeight() const { return height; }

private:

    GLuint loadShader(GLenum type, const char* source);
    GLuint createProgram(const char* vs_source, const char* fs_source);
    static const char* getVertexShader();
    static const char* getYFragmentShader();
    static const char* getUVFragmentShader();
};


template<typename Func>
void YuvConverter::process(GLuint rgba_tex, float* fix_matrix, Func data_callback) {
    if (y_program == 0 || uv_program == 0) return;

    static float identity[16] = {
        1, 0, 0, 0, 0, 1, 0, 0,
        0, 0, 1, 0, 0, 0, 0, 1
    };
    float* matrix = fix_matrix ? fix_matrix : identity;

    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);

    static const GLfloat vertices[] = {-1.f, -1.f, 1.f, -1.f, -1.f, 1.f, 1.f, 1.f};
    static const GLfloat texcoords[] = {0.f, 0.f, 1.f, 0.f, 0.f, 1.f, 1.f, 1.f};
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, vertices);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 0, texcoords);
    glEnableVertexAttribArray(1);

    glBindFramebuffer(GL_FRAMEBUFFER, y_fbo);
    glViewport(0, 0, width, height);
    glUseProgram(y_program);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, rgba_tex);
    glUniform1i(y_u_tex_loc, 0);
    glUniformMatrix4fv(y_u_fix_matrix_loc, 1, GL_FALSE, matrix);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glBindFramebuffer(GL_FRAMEBUFFER, uv_fbo);
    glViewport(0, 0, width / 2, height / 2);
    glUseProgram(uv_program);

    glUniform1i(uv_u_tex_loc, 0);
    glUniformMatrix4fv(uv_u_fix_matrix_loc, 1, GL_FALSE, matrix);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    if (use_pbo) {
        pbo_y.startAsyncRead(y_fbo);
        pbo_uv.startAsyncRead(uv_fbo);
        uint8_t* y_data = pbo_y.tryMapReadBuffer();
        if (y_data) {
            uint8_t* uv_data = pbo_uv.tryMapReadBuffer();
            if (uv_data) {
                memcpy(nv21_buffer.data(), y_data, width * height);
                memcpy(nv21_buffer.data() + width * height, uv_data, width * height / 2);
                data_callback(nv21_buffer.data(), nv21_buffer.size());
                
                pbo_uv.unmapReadBuffer();
            }

            pbo_y.unmapReadBuffer();
        }

        pbo_y.advance();
        pbo_uv.advance();
    } else {
        glBindFramebuffer(GL_FRAMEBUFFER, y_fbo);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, width, height, GL_RED, GL_UNSIGNED_BYTE, nv21_buffer.data());

        glBindFramebuffer(GL_FRAMEBUFFER, uv_fbo);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, width / 2, height / 2, GL_RG, GL_UNSIGNED_BYTE, nv21_buffer.data() + width * height);
        
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        data_callback(nv21_buffer.data(), nv21_buffer.size());
    }
}

#endif //CAMERA_MAGIC_YUV_CONVERTER_H
