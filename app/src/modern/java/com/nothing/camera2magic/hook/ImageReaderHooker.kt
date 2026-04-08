package com.nothing.camera2magic.hook

import android.media.Image
import android.media.ImageReader
import com.nothing.camera2magic.hook.NativeBridge as NB
import com.nothing.camera2magic.hook.SourceManager as SM
import com.nothing.camera2magic.MagicHook
import com.nothing.camera2magic.utils.Dog
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.Collections
import java.util.WeakHashMap

class ImageReaderHooker(val magic: MagicHook, param: PackageReadyParam) : HookManager {

    override val hookedClasses: MutableSet<Class<*>> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    companion object {
        private const val TAG = "[ImageReader]"
        private const val IMAGE_READER_CLASS = "android.media.ImageReader"
    }

    init {
        val classLoader = param.classLoader
        val irClass = classLoader.loadClass(IMAGE_READER_CLASS)
        irClass.safeHook {
            newInstance4Hook()
            newInstance5Hook()
            acquireNextImageHook()
        }
    }

    private fun Class<*>.newInstance4Hook() { // 4 参数版本
        val newInstance = getDeclaredMethod("newInstance",
            Int::class.java, Int::class.java, Int::class.java, Int::class.java)
        magic.hook(newInstance).intercept { chain ->
            val reader = chain.proceed() as ImageReader
            Dog.i(TAG, "[:newInstance] 4 args, ${reader.surface.shortId} format: ${reader.imageFormat}, size: ${reader.width}x${reader.height}", SM.enableLog)
            return@intercept reader
        }
    }
    private fun Class<*>.newInstance5Hook() { // 5 参数版本
        val newInstance = getDeclaredMethod("newInstance",
            Int::class.java, Int::class.java, Int::class.java, Int::class.java, Long::class.java)
        magic.hook(newInstance).intercept { chain ->
            val reader = chain.proceed() as ImageReader
            val args = chain.args
            Dog.i(TAG, "[:newInstance] 5 args, ${reader.surface.shortId}, format: ${args[2]}, size: ${args[0]}x${args[1]}", SM.enableLog)
            return@intercept reader
        }
    }

    private fun Class<*>.acquireNextImageHook() {
        val acquireNextImage = getDeclaredMethod("acquireNextImage")
        magic.hook(acquireNextImage).intercept { chain ->
            val reader = chain.thisObject as ImageReader
            if (!Camera3.isHijacked(reader.surface)) return@intercept chain.proceed()
            val image = chain.proceed() as? Image ?: return@intercept null
            val format = image.format

            if (format == 33) {
                Dog.w(TAG, "app wanna take a picture.")
                return@intercept image
            }

            if (format == 35) handleFormat35(image)

            return@intercept image
        }
    }

    private fun handleFormat35(image: Image) {
        val yPlane = image.planes[0] // 获取三个平面数据

        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        val uPlane = image.planes[1]
        val uBuffer = uPlane.buffer
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride

        val vPlane = image.planes[2]
        val vBuffer = vPlane.buffer
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        NB.overwriteYuvBuffer(yBuffer, yRowStride, yPixelStride,
            uBuffer, uRowStride, uPixelStride, vBuffer, vRowStride, vPixelStride)
    }
}