#ifndef CAMERA_MAGIC_MATRIX_UTILS_H
#define CAMERA_MAGIC_MATRIX_UTILS_H
#include "work_mode.h"
#include <string>
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace MatrixUtils {

/**
 * 设置单位矩阵
 * @param m 4x4 矩阵（列主序）
 */
void setIdentity(float* m);

/**
 * 矩阵乘法：result = a * b
 * @param result 结果矩阵（可以是 a 或 b 的别名）
 * @param a 左矩阵
 * @param b 右矩阵
 */
void multiply(float* result, const float* a, const float* b);

/**
 * 设置旋转矩阵（绕 Z 轴）
 * @param m 输出矩阵
 * @param angle_degrees 旋转角度（度）
 */
void setRotate(float* m, float angle_degrees);

/**
 * 设置缩放矩阵
 * @param m 输出矩阵
 * @param sx X 轴缩放
 * @param sy Y 轴缩放
 */
void setScale(float* m, float sx, float sy);

/**
 * 设置平移矩阵
 * @param m 输出矩阵
 * @param tx X 轴平移
 * @param ty Y 轴平移
 */
void setTranslate(float* m, float tx, float ty);

/**
 * 在中心点周围应用变换（平移到原点 -> 变换 -> 平移回来）
 * @param result 输出矩阵
 * @param transform 要应用的变换矩阵
 * @param center_x 中心点 X
 * @param center_y 中心点 Y
 */
void applyTransformAroundCenter(float* result, const float* transform,
                                 float center_x, float center_y);

/*
 * 预览画面的修正
 * 注意区分视频的物理分辨率/视觉分辨率/以及预览surface的物理分辨率/逻辑分辨率
 * @param out_matrix 输出矩阵
 */
void previewFixMatrix(float* out_matrix, int view_width, int view_height, int visual_video_width, int visual_video_height, int display_orientation, bool is_front_camera, int api_level, WorkMode work_mode);

/*
 * NV21数据修正
 * 将解码帧的nv21数据模拟成相机输入
 */
void nv21FixMatrix(float* out_matrix, int view_width, int view_height, int visual_video_width, int visual_video_height, bool is_front_camera, WorkMode work_mode);

} // namespace MatrixUtils

#endif // CAMERA_MAGIC_MATRIX_UTILS_H