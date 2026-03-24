package com.nothing.camera2magic.hook
import android.view.Surface
import java.util.WeakHashMap

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

data class SurfaceMetadata(
    val isActive: Boolean,
    val format: Int,
    val previewWidth: Int,
    val previewHeight: Int,
    val pictureWidth: Int,
    val pictureHeight: Int
)

data class CameraData(
    // 基础数据
    val api: Int,
    val facingFront: Boolean,
    val sensorOrientation: Int,
    val displayOrientation: Int,
    // 扩展数据
    val surfaces: WeakHashMap<Surface, SurfaceMetadata>
)