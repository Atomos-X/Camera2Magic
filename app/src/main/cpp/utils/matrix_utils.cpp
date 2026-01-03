#include "matrix_utils.h"
#include <string>
#include <cmath>

namespace MatrixUtils {

    void setIdentity(float *m) {
        memset(m, 0, 16 * sizeof(float));
        m[0] = m[5] = m[10] = m[15] = 1.0f;
    }

    void multiply(float *result, const float *a, const float *b) {
        float temp[16];
        for (int c = 0; c < 4; c++) {     // 遍历结果的列 (Column)
            for (int r = 0; r < 4; r++) { // 遍历结果的行 (Row)
                float sum = 0.0f;
                for (int k = 0; k < 4; k++) {
                    // a(r, k) * b(k, c)
                    // 列主序索引: index = col * 4 + row
                    int a_idx = k * 4 + r;
                    int b_idx = c * 4 + k;
                    sum += a[a_idx] * b[b_idx];
                }
                temp[c * 4 + r] = sum;
            }
        }
        memcpy(result, temp, 16 * sizeof(float));
    }

    void setRotate(float *m, float angle_degrees) {
        setIdentity(m);
        float rad = angle_degrees * (float) M_PI / 180.0f;
        float c = cosf(rad);
        float s = sinf(rad);
        // | c -s  0  0 |
        // | s  c  0  0 |
        m[0] = c;
        m[4] = -s;
        m[1] = s;
        m[5] = c;
    }

    void setScale(float *m, float sx, float sy) {
        setIdentity(m);
        m[0] = sx;
        m[5] = sy;
    }

    void setTranslate(float *m, float tx, float ty) {
        setIdentity(m);
        // 平移量在第 4 列 (索引 12, 13)
        m[12] = tx;
        m[13] = ty;
    }

    void applyTransformAroundCenter(float *out, float scale_x, float scale_y, float rotate_deg,
                                    bool mirror, bool flip_y) {
        float m_tmp[16], m_next[16];

        // 步骤 1: 移回原点 T(-0.5)
        setTranslate(out, -0.5f, -0.5f);

        // 步骤 2a: Y 翻转 (Flip Y)
        if (flip_y) {
            setScale(m_next, 1.0f, -1.0f);
            multiply(m_tmp, m_next, out);
            memcpy(out, m_tmp, 16 * sizeof(float));
        }
        // 步骤 2b: 镜像 (Mirror)
        // 乘法顺序: New = Mirror * Old
        if (mirror) {
            setScale(m_next, -1.0f, 1.0f);
            multiply(m_tmp, m_next, out);
            memcpy(out, m_tmp, 16 * sizeof(float));
        }
        // 步骤 3: 缩放 (Scale - 用于 Crop)
        if (scale_x != 1.0f || scale_y != 1.0f) {
            setScale(m_next, scale_x, scale_y);
            multiply(m_tmp, m_next, out);
            memcpy(out, m_tmp, 16 * sizeof(float));
        }
        // 步骤 4: 旋转 (Rotate)
        if (abs((int) rotate_deg) > 0) {
            setRotate(m_next, rotate_deg);
            multiply(m_tmp, m_next, out);
            memcpy(out, m_tmp, 16 * sizeof(float));
        }
        // 步骤 5: 移回中心 T(+0.5)
        setTranslate(m_next, 0.5f, 0.5f);
        multiply(m_tmp, m_next, out);
        memcpy(out, m_tmp, 16 * sizeof(float));
    }

    void previewFixMatrix(float* out_matrix, int view_width, int view_height, int visual_video_width, int visual_video_height, int display_orientation, bool is_front_camera, int api_level, WorkMode work_mode) {
        // api level = 1
        bool need_mirror = false; // app did itself
        bool need_flip_y = false; // app did itself
        int final_rotation = (display_orientation - 90 + 360) % 360;
        if (api_level == 2) {
            need_mirror = is_front_camera;
        }
        // 逻辑分辨率，用于缩放裁剪
        int logical_video_width = visual_video_width, logical_video_height = visual_video_height;
        int logical_view_width = view_width, logical_view_height = view_height;

        if (final_rotation == 90 || final_rotation == 270) {
            logical_video_width = visual_video_height;
            logical_video_height = visual_video_width;
        }

        bool need_switch_w_h = (work_mode == NORMAL) && (display_orientation == 90 || display_orientation == 270);
        if (need_switch_w_h) {
            logical_view_width = view_height;
            logical_view_height = view_width;
        }

        float video_aspect = (float)logical_video_width / (float)logical_video_height;
        float view_aspect = (float)logical_view_width / (float)logical_view_height;

        float scale_x = 1.0f, scale_y = 1.0f;
        if (video_aspect > view_aspect) {
            scale_x = view_aspect / video_aspect;
        } else {
            scale_y = video_aspect / view_aspect;
        }

        applyTransformAroundCenter(out_matrix, scale_x, scale_y, (float)final_rotation, need_mirror, need_flip_y);
    }

    void nv21FixMatrix(float* out_matrix, int view_width, int view_height, int visual_video_width, int visual_video_height, bool is_front_camera, WorkMode work_mode) {
        bool need_mirror = false;
        bool need_flip_y = true;
        int final_rotation = 0;
        // 逻辑分辨率，用于缩放裁剪
        int logical_view_width = view_width, logical_view_height = view_height;
        int logical_video_width = visual_video_width, logical_video_height = visual_video_height;
        if (work_mode == NORMAL) {
            logical_view_width = view_height;
            logical_view_height = view_width;
        }
        float video_aspect = (float)logical_video_width / (float)logical_video_height;
        float view_aspect = (float)logical_view_width / (float)logical_view_height;

        float scale_x = 1.0f, scale_y = 1.0f;
        if (video_aspect > view_aspect) {
            scale_x = view_aspect / video_aspect;
        } else {
            scale_y = video_aspect / view_aspect;
        }

        applyTransformAroundCenter(out_matrix, scale_x, scale_y, (float)final_rotation, need_mirror, need_flip_y);
    }

} // namespace MatrixUtils