package com.nothing.camera2magic.hook
import android.view.Surface
import android.hardware.camera2.CameraDevice

data class CameraState(
    var packageName: String = "",
    var apiLevel: Int = 0,
    var facingFront: Boolean = false,
    var pictureWidth: Int = 0,
    var pictureHeight: Int = 0,
    var previewWidth: Int = 0,
    var previewHeight: Int = 0,
    var sensorOrientation: Int = 90,
    var displayOrientation: Int = 0,
    var surface: Surface? = null,
)