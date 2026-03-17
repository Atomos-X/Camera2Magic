@file:Suppress("DEPRECATION")

package com.nothing.camera2magic.hook

import android.annotation.SuppressLint
import android.hardware.Camera
import android.view.Surface
import android.view.SurfaceHolder
import android.graphics.SurfaceTexture
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.MagicHook
import com.nothing.camera2magic.utils.Dog
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.Timer
import java.util.WeakHashMap
import kotlin.concurrent.schedule

object Camera1Hooker {
    private const val TAG = "[CAM1]"

    private val Camera?.shortId : String
        get() = if (this == null) "null" else "@0x${Integer.toHexString(System.identityHashCode(this))}"

    private var activeCameraRef: WeakReference<Camera>? = null
    private var cameraState = WeakHashMap<Camera, CameraState>()
    private var pushMode = false
    private var blackHole: Any? = null
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
    private fun getCameraState(camera: Camera): CameraState {
        return synchronized(cameraState) {
            cameraState.getOrPut(camera) { CameraState() }
        }
    }
    private fun isPreviewing(camera: Camera): Boolean {
        return activeCameraRef?.get() === camera
    }

    private lateinit var magic: MagicHook
    private val hookedCallbackClasses = mutableSetOf<Class<*>>()

    fun initHooks(module: MagicHook, param: PackageReadyParam) {
        magic = module
        Camera::class.java.apply {
            hookOpenMethod()
            hookSetParameters()
            hookSetPreviewTexture()
            hookSetPreviewDisplay()
            hookSetDisplayOrientation()
            hookStartPreview()
            hookStopPreview()
            hookRelease()
            hookSetPreviewCallback()
            hookAddCallbackBuffer()
            hookTakePicture()
        }
    }
    private val openInterceptor: (Chain) -> Any? = intercept@{ chain ->
        val camera = chain.proceed() as? Camera ?: return@intercept null
        activeCameraRef = WeakReference(camera)
        val cameraId = chain.args.getOrNull(0) as? Int ?: 0
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(cameraId, info)
        val state = getCameraState(camera)

        state.apiLevel = 1
        state.facingFront = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
        state.sensorOrientation = info.orientation
        state.packageName = GlobalState.packageName
        camera
    }
    private fun Class<*>.hookOpenMethod() {
        val open = getDeclaredMethod("open")
        val openId = getDeclaredMethod("open", Int::class.java)
        magic.hook(open).intercept(openInterceptor)
        magic.hook(openId).intercept(openInterceptor)
    }
    private fun Class<*>.hookSetParameters() {
        val setParameters = getDeclaredMethod("setParameters", Camera.Parameters::class.java)
        magic.hook(setParameters).intercept { chain ->
            chain.proceed()
            val camera = chain.thisObject as Camera
            val params = chain.args[0] as Camera.Parameters
            val pictureSize = params.pictureSize
            val previewSize = params.previewSize
            val state = getCameraState(camera)
            if (state.pictureWidth != pictureSize.width || state.pictureHeight != pictureSize.height) {
                state.pictureWidth = pictureSize.width
                state.pictureHeight = pictureSize.height
            }
            if (state.previewWidth != previewSize.width || state.previewHeight != previewSize.height) {
                state.previewWidth = previewSize.width
                state.previewHeight = previewSize.height
            }
        }
    }
    private fun Class<*>.hookSetPreviewTexture() {
        val setPreviewTexture = getDeclaredMethod("setPreviewTexture", SurfaceTexture::class.java)
        magic.hook(setPreviewTexture).intercept { chain ->
            if (!SourceManager.isReadyForHook()) return@intercept chain.proceed()
            val camera = chain.thisObject as Camera
            val surfaceTexture = chain.args[0] as SurfaceTexture
            val state = getCameraState(camera)

            @SuppressLint("Recycle")
            state.surface = Surface(surfaceTexture)
            val fakeSurfaceTexture = SurfaceTexture(false)
                .apply { setDefaultBufferSize(1, 1) }

            blackHole = fakeSurfaceTexture.also { chain.proceed(arrayOf(it)) }
        }
    }
    private fun Class<*>.hookSetPreviewDisplay() {
        val setPreviewDisplay = getDeclaredMethod(
            "setPreviewDisplay",
            SurfaceHolder::class.java)
        magic.hook(setPreviewDisplay).intercept { chain ->
            if (!SourceManager.isReadyForHook()) return@intercept chain.proceed()

            pushMode = true
            val camera = chain.thisObject as Camera
            val holder = chain.args[0] as SurfaceHolder
            val state = getCameraState(camera)
            state.surface = holder.surface

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
    private fun Class<*>.hookSetDisplayOrientation() {
        val setDisplayOrientation = getDeclaredMethod(
            "setDisplayOrientation",
            Int::class.javaPrimitiveType)

        magic.hook(setDisplayOrientation).intercept { chain ->
            val camera = chain.thisObject as Camera
            val state = getCameraState(camera)
            val displayOrientation = chain.args[0] as Int
            if (!SourceManager.isReadyForHook() || state.displayOrientation == displayOrientation) return@intercept chain.proceed()
            state.displayOrientation = displayOrientation
            if (isPreviewing(camera)) {
                NativeBridge.setDisplayOrientation(displayOrientation)
            }
            chain.proceed()
        }
    }
    private fun Class<*>.hookStartPreview() {
        val startPreview = getDeclaredMethod("startPreview")
        magic.hook(startPreview).intercept { chain ->
            if (!SourceManager.isReadyForHook()) return@intercept chain.proceed()
            val camera = chain.thisObject as Camera
            val state = getCameraState(camera)
            val activeCamera = activeCameraRef?.get()
            if (activeCamera != null && camera === activeCamera) {
                NativeBridge.registerSurfaceIfNew(state, true)
                NativeBridge.needStartRenderer()
            }
            chain.proceed()
        }
    }
    private fun Class<*>.hookStopPreview() {
        val stopPreview = getDeclaredMethod("stopPreview")
        magic.hook(stopPreview).intercept { chain ->
            if (!SourceManager.isReadyForHook()) return@intercept chain.proceed()
            val camera = chain.thisObject as Camera
            val activeCamera = activeCameraRef?.get()
            if (activeCamera != null && camera === activeCamera) {
                NativeBridge.needStopRenderer()
            }
            chain.proceed()
        }
    }
    private fun Class<*>.hookRelease() {
        val release = getDeclaredMethod("release")
        magic.hook(release).intercept { chain ->
            if (!SourceManager.isReadyForHook()) return@intercept chain.proceed()
            val closingCamera = chain.thisObject as Camera
            val activeCamera = activeCameraRef?.get()

            if (activeCamera != null && closingCamera === activeCamera) {
                NativeBridge.needStopRenderer()
                NativeBridge.releaseLastRegisteredSurface()
                destroyBlackHole()
                activeCameraRef = null
            }
            chain.proceed()
        }
    }

    private val previewCallbackInterceptor: (Chain) -> Any? = intercept@ { chain ->
        if (!SourceManager.isReadyForHook()) return@intercept chain.proceed()
        val originCallback = chain.args[0] as? Camera.PreviewCallback ?: return@intercept chain.proceed()
        val clazz = originCallback.javaClass
        synchronized(hookedCallbackClasses) {
            if (hookedCallbackClasses.add(clazz)) {
                val onPreviewFrame = clazz.getDeclaredMethod("onPreviewFrame",
                    ByteArray::class.java,
                    Camera::class.java)
                magic.hook(onPreviewFrame).intercept { frameChain ->
                    val originBuffer = frameChain.args[0] as ByteArray
                    NativeBridge.overwritePreviewBuffer(originBuffer)
                    frameChain.proceed()
                }
            }
        }
        chain.proceed()
    }

    private fun Class<*>.hookSetPreviewCallback() {
        val setPreviewCallback = getDeclaredMethod(
            "setPreviewCallback",
            Camera.PreviewCallback::class.java)
        val setPreviewCallbackWithBuffer = getDeclaredMethod(
            "setPreviewCallbackWithBuffer",
            Camera.PreviewCallback::class.java)
        magic.hook(setPreviewCallback).intercept(previewCallbackInterceptor)
        magic.hook(setPreviewCallbackWithBuffer).intercept(previewCallbackInterceptor)
    }

    private fun Class<*>.hookAddCallbackBuffer() {
        val addCallbackBuffer = getDeclaredMethod("addCallbackBuffer",
            ByteArray::class.java)
        // TODO:
    }

    private fun Class<*>.hookTakePicture() {
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
                synchronized(hookedCallbackClasses) {
                    if (hookedCallbackClasses.add(clazz)) {
                        val onPictureTaken = clazz.getDeclaredMethod("onPictureTaken",
                            ByteArray::class.java, Camera::class.java)
                        magic.hook(onPictureTaken).intercept { shot ->
                            val newArgs = shot.args.toTypedArray()
                            newArgs[0] = NativeBridge.overwriteJPEGBytes()
                            shot.proceed(newArgs)
                        }
                    }
                }
            }
            chain.proceed()
        }
    }
}