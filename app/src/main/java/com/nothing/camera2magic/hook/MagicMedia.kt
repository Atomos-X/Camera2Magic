package com.nothing.camera2magic.hook

import android.net.Uri

enum class MagicType(val value: Int, val label: String) {
    LOCAL_VIDEO(0x0000, "video"),
    LOCAL_IMAGE(0x0001, "image"),
    NETWORK_RTSP(0x0100, "rtsp");
    companion object {
        fun fromValue(value: Int): MagicType {
            return entries.find { it.value == value }
                ?: throw IllegalArgumentException("Invalid MediaSource value: $value")
        }
    }
}

data class ValidMedia (
    var file: String,
    var type: MagicType
)
