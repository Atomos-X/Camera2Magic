#include "camera_state.h"

void CameraState::reset() {
    camera_id.clear();
    sensor_orientation = 0;
    picture_width = 0;
    picture_height = 0;
    preview_surface = nullptr;
    preview_width = 0;
    preview_height = 0;
    display_orientation = 0;
    last_printed_camera_id.clear();
    preview_surface_version = 0;
    last_printed_preview_surface_version = -1;
}

void CameraState::updateCameraParameters(const std::string& id, int orientation, 
                                          int pic_w, int pic_h) {
    camera_id = id;
    sensor_orientation = orientation;
    picture_width = pic_w;
    picture_height = pic_h;
}

void CameraState::updatePreviewSize(int w, int h) {
    preview_width = w;
    preview_height = h;
}

void CameraState::setDisplayOrientation(int orientation) {
    display_orientation = orientation;
}

void CameraState::registerPreviewSurface(jobject surface) {
    preview_surface = surface;
    preview_surface_version++;
}

bool CameraState::isFrontCamera() const {
    return camera_id == "1";
}

bool CameraState::hasChanged() const {
    return camera_id != last_printed_camera_id || 
           preview_surface_version != last_printed_preview_surface_version;
}

void CameraState::updatePrintedState() {
    last_printed_camera_id = camera_id;
    last_printed_preview_surface_version = preview_surface_version;
}