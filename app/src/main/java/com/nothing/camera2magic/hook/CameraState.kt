package com.nothing.camera2magic.hook

import android.view.Surface

data class CameraState(
    // camera state
    var cameraId: String = "-1",
    var sensorOrientation: Int = 0,
    var pictureWidth: Int = 1080,
    var pictureHeight: Int = 1920,

    // preview surface state
    var surface: Surface? = null,
    var displayOrientation: Int = 0
)