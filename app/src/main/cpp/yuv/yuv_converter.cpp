#include "yuv_converter.h"
#include "../utils/log_utils.h"

#define TAG "[YuvConverter]"

const char* YuvConverter::getVertexShader() {
    return "#version 300 es\n"
           "layout(location = 0) in vec4 a_Position;\n"
           "layout(location = 1) in vec2 a_TexCoord;\n"
           "uniform mat4 u_FixMatrix;\n"
           "out vec2 v_TexCoord;\n"
           "void main() {\n"
           "    gl_Position = a_Position;\n"
           "    v_TexCoord = (u_FixMatrix * vec4(a_TexCoord, 0.0, 1.0)).xy;\n"
           "}\n";
}

// Y-Plane Fragment Shader: Converts RGBA to Luminance (Y).
const char* YuvConverter::getYFragmentShader() {
    return "#version 300 es\n"
           "precision highp float;\n"
           "in vec2 v_TexCoord;\n"
           "layout(location = 0) out float o_Luma;\n"
           "uniform sampler2D u_Texture;\n"
           "const vec3 COEFF_Y = vec3(0.299, 0.587, 0.114);\n"
           "void main() {\n"
           "    vec3 rgb = texture(u_Texture, v_TexCoord).rgb;\n"
           "    o_Luma = dot(rgb, COEFF_Y);\n"
           "}\n";
}

// UV-Plane Fragment Shader: Converts RGBA to Chrominance (VU interleaved for NV21).
const char* YuvConverter::getUVFragmentShader() {
    return "#version 300 es\n"
           "precision highp float;\n"
           "in vec2 v_TexCoord;\n"
           "layout(location = 0) out vec2 o_Chroma;\n"
           "uniform sampler2D u_Texture;\n"
           "const vec3 COEFF_V = vec3(0.500, -0.419, -0.081);\n"
           "const vec3 COEFF_U = vec3(-0.169, -0.331, 0.500);\n"
           "void main() {\n"
           "    vec3 rgb = texture(u_Texture, v_TexCoord).rgb;\n"
           "    float v = dot(rgb, COEFF_V) + 0.5;\n"
           "    float u = dot(rgb, COEFF_U) + 0.5;\n"
           "    o_Chroma = vec2(v, u);\n"
           "}\n";
}

GLuint YuvConverter::loadShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    if (shader == 0) {
        LOGE(TAG, "Failed to create shader of type 0x%X", type);
        return 0;
    }
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
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

GLuint YuvConverter::createProgram(const char* vs_source, const char* fs_source) {
    GLuint vs = loadShader(GL_VERTEX_SHADER, vs_source);
    if (vs == 0) return 0;
    GLuint fs = loadShader(GL_FRAGMENT_SHADER, fs_source);
    if (fs == 0) {
        glDeleteShader(vs);
        return 0;
    }

    GLuint program = glCreateProgram();
    if (program == 0) {
        LOGE(TAG,"Failed to create program");
    } else {
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);
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
        }
    }
    glDeleteShader(vs);
    glDeleteShader(fs);
    return program;
}

bool YuvConverter::init(int target_width, int target_height, bool enable_pbo) {
    width = target_width;
    height = target_height;
    use_pbo = enable_pbo;
    nv21_buffer.resize(width * height * 3 / 2);

    // 1. Create programs
    y_program = createProgram(getVertexShader(), getYFragmentShader());
    uv_program = createProgram(getVertexShader(), getUVFragmentShader());
    if (y_program == 0 || uv_program == 0) {
        LOGE(TAG, "Failed to create shader programs.");
        destroy();
        return false;
    }

    y_u_tex_loc = glGetUniformLocation(y_program, "u_Texture");
    y_u_fix_matrix_loc = glGetUniformLocation(y_program, "u_FixMatrix");
    uv_u_tex_loc = glGetUniformLocation(uv_program, "u_Texture");
    uv_u_fix_matrix_loc = glGetUniformLocation(uv_program, "u_FixMatrix");

    // 2. Create Y-plane resources
    glGenTextures(1, &y_texture);
    glBindTexture(GL_TEXTURE_2D, y_texture);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, width, height, 0, GL_RED, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    glGenFramebuffers(1, &y_fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, y_fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, y_texture, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        LOGE(TAG, "Y-FBO is not complete.");
        destroy();
        return false;
    }

    // 3. Create UV-plane resources
    glGenTextures(1, &uv_texture);
    glBindTexture(GL_TEXTURE_2D, uv_texture);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RG8, width / 2, height / 2, 0, GL_RG, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    glGenFramebuffers(1, &uv_fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, uv_fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, uv_texture, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        LOGE(TAG, "UV-FBO is not complete.");
        destroy();
        return false;
    }

    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    // 4. Initialize PBOs
    if (use_pbo) {
        if (!pbo_y.init(width, height, 1) || !pbo_uv.init(width / 2, height / 2, 2)) {
            LOGW(TAG, "PBO initialization failed, falling back to synchronous mode.");
            use_pbo = false;
        }
    }

    LOGI(TAG, "GPU YUV converter initialized. Mode: %s, %dx%d", use_pbo ? "PBO" : "Sync", width, height);
    return true;
}

void YuvConverter::destroy() {
    if (y_program) glDeleteProgram(y_program);
    y_program = 0;
    if (uv_program) glDeleteProgram(uv_program);
    uv_program = 0;

    if (y_fbo) glDeleteFramebuffers(1, &y_fbo);
    y_fbo = 0;
    if (uv_fbo) glDeleteFramebuffers(1, &uv_fbo);
    uv_fbo = 0;

    if (y_texture) glDeleteTextures(1, &y_texture);
    y_texture = 0;
    if (uv_texture) glDeleteTextures(1, &uv_texture);
    uv_texture = 0;

    if (pbo_y.isInitialized()) pbo_y.destroy();
    if (pbo_uv.isInitialized()) pbo_uv.destroy();

    nv21_buffer.clear();
    width = 0;
    height = 0;
}
