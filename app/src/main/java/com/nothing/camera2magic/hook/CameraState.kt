package com.nothing.camera2magic.hook

import android.view.Surface

data class CameraState(
    var apiLevel: Int = 0,
    // camera state
    var cameraId: String = "0",
    var sensorOrientation: Int = 0,
    var pictureWidth: Int = 1920,
    var pictureHeight: Int = 1080,
    // preview surface state
    var displayOrientation: Int = 0,
    var surface: Surface? = null
)