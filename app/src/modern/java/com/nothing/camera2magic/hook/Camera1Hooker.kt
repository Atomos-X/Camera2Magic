@file:Suppress("DEPRECATION")

package com.nothing.camera2magic.hook

import android.annotation.SuppressLint
import android.hardware.Camera
import android.view.Surface
import android.view.SurfaceHolder
import android.graphics.SurfaceTexture
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.MagicHook
import com.nothing.camera2magic.hook.NativeBridge as NB
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

class Camera1Hooker(val magic: MagicHook, val param: PackageReadyParam) : HookManager  {
    companion object {
        private const val TAG = "[CAM1]"

        private const val CLS_CAMERA = "android.hardware.Camera"
        private var blackHole: Any? = null
        private lateinit var openInterceptor: (Chain) -> Any?
        private lateinit var previewCallbackInterceptor: (Chain) -> Any?
        private val Camera.state: CameraState
            get() = CameraRegistry.obtain(this) { apiLevel = 1 }
        private val Camera.isActiveRef: Boolean
            get() = CameraRegistry.isActive(this)
    }
    override val hookedClasses: MutableSet<Class<*>> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    private fun destroyBlackHole() {
        when (blackHole) {
            is SurfaceTexture -> {
                (blackHole as SurfaceTexture).release()
            }
            is Surface -> {
                (blackHole as Surface).release()
            }
        }
        blackHole = null
    }

    init {
        openInterceptor = intercept@{ chain ->
            val camera = chain.proceed() as? Camera ?: return@intercept null
            val cameraId = chain.args.getOrNull(0) as? Int ?: 0
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(cameraId, info)

            camera.state.apply {
                this.facingFront = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
                this.sensorOrientation = info.orientation
                this.packageName = GlobalState.packageName
            }

            return@intercept camera
        }

        previewCallbackInterceptor = intercept@ { chain ->
            if (!SourceManager.isReadyForHook()) return@intercept chain.proceed()
            val originCallback = chain.args[0] as? Camera.PreviewCallback ?: return@intercept chain.proceed()
            originCallback.javaClass.safeHook { onPreviewFrameHook() }
            chain.proceed()
        }

        param.classLoader.safeHook(CLS_CAMERA) {
            openHook()
            setParametersHook()
            setPreviewTextureHook()
            setPreviewDisplayHook()
            setDisplayOrientationHook()
            startPreviewHook()
            stopPreviewHook()
            releaseHook()
            setPreviewCallbackHook()
            addCallbackBufferHook()
            takePictureHook()
        }
    }
    private fun Class<*>.openHook() {
        val open = getDeclaredMethod("open")
        val openId = getDeclaredMethod("open", Int::class.java)
        magic.hook(open).intercept(openInterceptor)
        magic.hook(openId).intercept(openInterceptor)
    }
    private fun Class<*>.setParametersHook() {
        val setParameters = getDeclaredMethod("setParameters", Camera.Parameters::class.java)
        magic.hook(setParameters).intercept { chain ->
            chain.proceed()
            val camera = chain.thisObject as Camera
            val params = chain.args[0] as Camera.Parameters
            val pictureSize = params.pictureSize
            val previewSize = params.previewSize
            camera.state.apply {
                this.pictureWidth = pictureSize.width
                this.pictureHeight = pictureSize.height
                this.previewWidth = previewSize.width
                this.previewHeight = previewSize.height
            }
        }
    }
    private fun Class<*>.setPreviewTextureHook() {
        val setPreviewTexture = getDeclaredMethod("setPreviewTexture",
            SurfaceTexture::class.java)
        magic.hook(setPreviewTexture).intercept { chain ->
            if (!SourceManager.isReadyForHook()) return@intercept chain.proceed()
            val camera = chain.thisObject as Camera
            val surfaceTexture = chain.args[0] as SurfaceTexture

            @SuppressLint("Recycle")
            camera.state.apply { this.surface = Surface(surfaceTexture) }

            val fakeSurfaceTexture = SurfaceTexture(false)
                .apply { setDefaultBufferSize(1, 1) }

            blackHole = fakeSurfaceTexture.also { chain.proceed(arrayOf(it)) }
        }
    }
    private fun Class<*>.setPreviewDisplayHook() {
        val setPreviewDisplay = getDeclaredMethod(
            "setPreviewDisplay",
            SurfaceHolder::class.java)
        magic.hook(setPreviewDisplay).intercept { chain ->
            if (!SourceManager.isReadyForHook()) return@intercept chain.proceed()
            val camera = chain.thisObject as Camera
            val holder = chain.args[0] as SurfaceHolder
            camera.state.apply { this.surface = holder.surface }
            @SuppressLint("Recycle")
            val surfaceTexture = SurfaceTexture(false)
                .apply { setDefaultBufferSize(1, 1) }
            val surface = Surface(surfaceTexture).also { blackHole = it }
            val surfaceHolderProxy = Proxy.newProxyInstance(holder.javaClass.classLoader,
                arrayOf(SurfaceHolder::class.java)) { _, method, args ->
                if (method.name == "getSurface") return@newProxyInstance surface
                return@newProxyInstance method.invoke(holder, *(args ?: arrayOfNulls<Any>(0)))
            } as SurfaceHolder
            chain.proceed(arrayOf(surfaceHolderProxy))
        }
    }
    private fun Class<*>.setDisplayOrientationHook() {
        val setDisplayOrientation = getDeclaredMethod(
            "setDisplayOrientation",
            Int::class.javaPrimitiveType)

        magic.hook(setDisplayOrientation).intercept { chain ->
            val camera = chain.thisObject as Camera
            val displayOrientation = chain.args[0] as Int
            if (!SourceManager.isReadyForHook() || camera.state.displayOrientation == displayOrientation) return@intercept chain.proceed()
            camera.state.displayOrientation = displayOrientation
            if (camera.isActiveRef) NB.setDisplayOrientation(displayOrientation)
            chain.proceed()
        }
    }
    private fun Class<*>.startPreviewHook() {
        val startPreview = getDeclaredMethod("startPreview")
        magic.hook(startPreview).intercept { chain ->
            if (!SourceManager.isReadyForHook()) return@intercept chain.proceed()
            val camera = chain.thisObject as Camera
            if (camera.isActiveRef) {
                NB.registerSurfaceIfNew(camera.state, true)
                NB.needStartRenderer()
            }
            chain.proceed()
        }
    }
    private fun Class<*>.stopPreviewHook() {
        val stopPreview = getDeclaredMethod("stopPreview")
        magic.hook(stopPreview).intercept { chain ->
            if (!SourceManager.isReadyForHook()) return@intercept chain.proceed()
            val camera = chain.thisObject as Camera
            if (camera.isActiveRef) NB.needStopRenderer()
            chain.proceed()
        }
    }
    private fun Class<*>.releaseHook() {
        val release = getDeclaredMethod("release")
        magic.hook(release).intercept { chain ->
            if (!SourceManager.isReadyForHook()) return@intercept chain.proceed()
            val camera = chain.thisObject as Camera
            if (camera.isActiveRef) {
                NB.needStopRenderer()
                NB.releaseLastRegisteredSurface()
                destroyBlackHole()
            }
            chain.proceed()
        }
    }
    private fun Class<*>.onPreviewFrameHook() {
        val onPreviewFrame = getDeclaredMethod("onPreviewFrame",
            ByteArray::class.java, Camera::class.java)
        magic.hook(onPreviewFrame).intercept { frame ->
            val originBuffer = frame.args[0] as ByteArray
            NB.overwritePreviewBuffer(originBuffer)
            frame.proceed()
        }
    }
    private fun Class<*>.setPreviewCallbackHook() {
        val setPreviewCallback = getDeclaredMethod(
            "setPreviewCallback",
            Camera.PreviewCallback::class.java)
        val setPreviewCallbackWithBuffer = getDeclaredMethod(
            "setPreviewCallbackWithBuffer",
            Camera.PreviewCallback::class.java)
        magic.hook(setPreviewCallback).intercept(previewCallbackInterceptor)
        magic.hook(setPreviewCallbackWithBuffer).intercept(previewCallbackInterceptor)
    }
    private fun Class<*>.addCallbackBufferHook() {
        val addCallbackBuffer = getDeclaredMethod("addCallbackBuffer",
            ByteArray::class.java)
        // TODO:
    }
    private fun Class<*>.onPictureTakenHook() {
        val onPictureTaken = getDeclaredMethod("onPictureTaken",
            ByteArray::class.java, Camera::class.java)
        magic.hook(onPictureTaken).intercept { shot ->
            val newArgs = shot.args.toTypedArray()
            newArgs[0] = NB.overwriteJPEGBytes()
            shot.proceed(newArgs)
        }
    }
    private fun Class<*>.takePictureHook() {
        val takePicture = getDeclaredMethod(
            "takePicture",
            Camera.ShutterCallback::class.java,
            Camera.PictureCallback::class.java, // raw
            Camera.PictureCallback::class.java, // post view
            Camera.PictureCallback::class.java) // jpeg

        magic.hook(takePicture).intercept { chain ->
            if (!SourceManager.isReadyForHook()) return@intercept chain.proceed()
            chain.args[3]?.let { cb ->
                val clazz = (cb as Camera.PictureCallback).javaClass
                clazz.safeHook {
                    onPictureTakenHook()
                }
            }
            chain.proceed()
        }
    }
}