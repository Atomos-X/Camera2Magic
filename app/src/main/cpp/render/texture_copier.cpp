#include "texture_copier.h"
#include "../utils/log_utils.h"
#include "../utils/matrix_utils.h"

#define TAG "[TextureCopier]"


const char* TextureCopier::getVertexShader() {
    return R"glsl(
        #version 300 es
        layout(location = 0) in vec4 a_Position;
        layout(location = 1) in vec4 a_TexCoord;
        uniform mat4 u_MVPMatrix; //控制几何体旋转
        uniform mat4 u_TexMatrix; //控制纹理坐标映射
        out vec2 v_TexCoord;
        void main() {
            gl_Position = u_MVPMatrix * a_Position;
            v_TexCoord = (u_TexMatrix * a_TexCoord).xy;
        }
    )glsl";
}

const char* TextureCopier::getFragmentShader() {
    return R"glsl(
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision highp float;
        in vec2 v_TexCoord;
        layout(location = 0) out vec4 o_FragColor;
        uniform samplerExternalOES u_OesTexture;
        void main() {
            o_FragColor = texture(u_OesTexture, v_TexCoord);
        }
    )glsl";
}

GLuint TextureCopier::loadShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    if (shader == 0) {
        LOGE(TAG, "Failed to create shader of type 0x%X", type);
        return 0;
    }

    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    // 检查编译状态
    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            char* infoLog = new char[infoLen];
            glGetShaderInfoLog(shader, infoLen, nullptr, infoLog);
            LOGE(TAG, "Shader compile error: %s", infoLog);
            delete[] infoLog;
        }
        glDeleteShader(shader);
        return 0;
    }

    return shader;
}

bool TextureCopier::init(int w, int h) {
    width = w;
    height = h;

    // 编译着色器
    GLuint vs = loadShader(GL_VERTEX_SHADER, getVertexShader());
    if (vs == 0) {
        LOGE(TAG, "Failed to load vertex shader");
        return false;
    }

    GLuint fs = loadShader(GL_FRAGMENT_SHADER, getFragmentShader());
    if (fs == 0) {
        LOGE(TAG, "Failed to load fragment shader");
        glDeleteShader(vs);
        return false;
    }

    // 链接程序
    program = glCreateProgram();
    if (program == 0) {
        LOGE(TAG, "Failed to create program");
        glDeleteShader(vs);
        glDeleteShader(fs);
        return false;
    }

    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);

    // 检查链接状态
    GLint linked = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        GLint infoLen = 0;
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            char* infoLog = new char[infoLen];
            glGetProgramInfoLog(program, infoLen, nullptr, infoLog);
            LOGE(TAG, "Program link error: %s", infoLog);
            delete[] infoLog;
        }
        glDeleteProgram(program);
        program = 0;
        glDeleteShader(vs);
        glDeleteShader(fs);
        return false;
    }

    // 删除着色器（已链接到程序中）
    glDeleteShader(vs);
    glDeleteShader(fs);

    // 获取 uniform 位置
    u_oes_tex_loc = glGetUniformLocation(program, "u_OesTexture");
    u_mvp_loc = glGetUniformLocation(program, "u_MVPMatrix"); // 新增
    u_tex_matrix_loc = glGetUniformLocation(program, "u_TexMatrix"); // 新增

    if (u_oes_tex_loc < 0) {
        LOGW(TAG, "Warning: uniform 'u_OesTexture' not found");
    }

    // 创建输出纹理
    glGenTextures(1, &output_tex);
    glBindTexture(GL_TEXTURE_2D, output_tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);

    // 创建 FBO
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                           GL_TEXTURE_2D, output_tex, 0);

    // 检查 FBO 状态
    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOGE(TAG, "FBO incomplete: 0x%X", status);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        destroy();
        return false;
    }

    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    LOGI(TAG, "Init OES->RGBA2D: %dx%d, Program=%u, Tex=%u, FBO=%u",
         width, height, program, output_tex, fbo);
    return true;
}

void TextureCopier::destroy() {
    if (fbo) {
        glDeleteFramebuffers(1, &fbo);
        fbo = 0;
    }
    if (output_tex) {
        glDeleteTextures(1, &output_tex);
        output_tex = 0;
    }
    if (program) {
        glDeleteProgram(program);
        program = 0;
    }
    width = 0;
    height = 0;
    u_oes_tex_loc = -1;
    LOGI(TAG, "Destroyed");
}

void TextureCopier::process(GLuint oes_tex_id, float* st_matrix, int video_rotation, GLuint target_fbo) {
    if (!program || !fbo || !output_tex) {
        LOGW(TAG, "Not initialized, skipping process");
        return;
    }
    GLuint final_fbo = (target_fbo != 0) ? target_fbo : this->fbo;

    if (final_fbo == 0 && this->fbo == 0) { return; }

    glBindFramebuffer(GL_FRAMEBUFFER, final_fbo);
    glViewport(0, 0, width, height);

    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);

    glUseProgram(program);
    int final_rotation = 0;
    float mpv_matrix[16];
    MatrixUtils::setIdentity(mpv_matrix);
    MatrixUtils::setRotate(mpv_matrix, (float)final_rotation);
    glUniformMatrix4fv(u_mvp_loc,1,GL_FALSE,mpv_matrix);

    if (st_matrix) {
        glUniformMatrix4fv(u_tex_matrix_loc, 1, GL_FALSE, st_matrix);
    } else {
        float identity[16];
        MatrixUtils::setIdentity(identity);
        glUniformMatrix4fv(u_tex_matrix_loc, 1, GL_FALSE, identity);
    }

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, oes_tex_id);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glUniform1i(u_oes_tex_loc, 0);

    static const GLfloat vertices[] = {
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
    };

    // 纹理坐标
    static const GLfloat texcoords[] = {
        0.0f, 0.0f,
        1.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 1.0f
    };

    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, vertices);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 0, texcoords);
    glEnableVertexAttribArray(1);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}