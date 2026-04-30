package com.nothing.camera2magic.hook

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