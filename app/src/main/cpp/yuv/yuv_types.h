#ifndef CAMERA_MAGIC_YUV_TYPES_H
#define CAMERA_MAGIC_YUV_TYPES_H

#include "../utils/work_mode.h"
#include <cstdint>
#include <GLES3/gl3.h>

struct YuvConversionTask {
    GLuint rgba_texture = 0;
    int width = 0;
    int height = 0;
    WorkMode work_mode = NORMAL;
    float fix_matrix[16];
    int64_t timestamp_us = 0;
    int frame_number = 0;
};

#endif // CAMERA_MAGIC_YUV_TYPES_H