#ifndef CAMERA_MAGIC_CAMERA_STATE_H
#define CAMERA_MAGIC_CAMERA_STATE_H

#include <string>
#include <jni.h>

struct CameraState {
    std::string camera_id;
    int sensor_orientation = 0;
    
    int picture_width = 0;
    int picture_height = 0;
    
    jobject preview_surface = nullptr;
    int preview_width = 0;
    int preview_height = 0;
    
    int display_orientation = 0;
    
    std::string last_printed_camera_id;
    std::atomic<int> preview_surface_version = {0};
    int last_printed_preview_surface_version = -1;

    CameraState() = default;

    // only update from JNI ... func not use
    void reset();

    void updateCameraParameters(const std::string& id, int orientation, 
                                int pic_w, int pic_h);
    void updatePreviewSize(int w, int h);

    void setDisplayOrientation(int orientation);

    void registerPreviewSurface(jobject surface);

    bool isFrontCamera() const;

    bool hasChanged() const;

    void updatePrintedState();
};

#endif // CAMERA_MAGIC_CAMERA_STATE_H