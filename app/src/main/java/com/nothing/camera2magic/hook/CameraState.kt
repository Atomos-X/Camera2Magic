package com.nothing.camera2magic.hook
import android.view.Surface
/**
 * @param apiLevel 1: Camera1, 2: Camera2 x: CameraX
 * @param sensorOrientation 90 / 270
 * @param pictureWidth not the final picture size
 * @param pictureHeight not the final picture size
 * @param packageName app package name
 * @param displayOrientation 0, 90, 180, 270
 * @param surface preview surface
 */

data class CameraState(
    var apiLevel: Int = 0,
    var facingFront: Boolean = false,
    var sensorOrientation: Int = 90,
    var pictureWidth: Int = 1920,
    var pictureHeight: Int = 1080,
    var packageName: String = "",
    var displayOrientation: Int = 0,
    var surface: Surface? = null
)