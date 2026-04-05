package com.nothing.camera2magic.hook

import android.util.Size
import android.view.Surface

enum class WorkMode(val value: Int, val label: String) {
    TAKE_PICTURE(0, "NORMAL"),
    NORMAL(1, "FACE DETECTION"),
    SCAN_QR_CODE(2, "SCAN QR CODE");

    companion object {
        fun mode(value: Int): String {
            val type = WorkMode.entries.find { it.value == value }
                ?: throw IllegalArgumentException("Invalid WorkMode value: $value")
            return type.label
        }
    }
}

data class CameraState (
    var api: Int = 0,
    var facingFront: Boolean = false,
    var sensorOri: Int = 0,
    var displayOri: Int = 0,
    var previewSize: Size = Size(0, 0),
    var pictureSize: Size = Size(0, 0),
    var surface: Surface? = null,
    var packageName: String = "",
)