package com.nothing.camera2magic.hook
import android.view.Surface
/**
 * @param apiLevel 1: Camera1, 2: Camera2 x: CameraX
 * @param cameraId 0 back camera, 1 front camera; set default to "0", for camera1 api only need hook Camera.open(with parameters)
 * @param sensorOrientation 90 / 270
 * @param pictureWidth not the final picture size
 * @param pictureHeight not the final picture size
 * @param displayOrientation 0, 90, 180, 270
 * @param surface preview surface
 */
data class CameraState(
    var apiLevel: Int = 0,
    var cameraId: String = "0",
    var sensorOrientation: Int = 90,
    var pictureWidth: Int = 1920,
    var pictureHeight: Int = 1080,
    var displayOrientation: Int = 0,
    var surface: Surface? = null
)