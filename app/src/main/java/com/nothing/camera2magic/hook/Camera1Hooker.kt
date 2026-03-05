@file:Suppress("DEPRECATION")

package com.nothing.camera2magic.hook

import android.hardware.Camera
import android.view.Surface
import android.view.SurfaceHolder
import android.graphics.SurfaceTexture
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.MagicEntry
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.BeforeHookCallback
import io.github.libxposed.api.XposedInterface.AfterHookCallback
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.ref.WeakReference
import java.util.WeakHashMap
object Camera1Hooker {
    private const val TAG = "[CAM1]"

    private val Camera?.shortId : String
        get() = if (this == null) "null" else "@0x${Integer.toHexString(System.identityHashCode(this))}"

    private var activeCameraRef: WeakReference<Any>? = null
    private var cameraState = WeakHashMap<Camera, CameraState>()
    private val surfaceCache = WeakHashMap<Camera, Any>()
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
    private fun getSurfaceFrom(obj: Any?): Surface? {
        return when (obj) {
            is SurfaceTexture -> Surface(obj)
            is Surface -> obj
            else -> null
        }
    }
    private fun CameraState.saveCameraInfo(info: Camera.CameraInfo) {
        this.apiLevel = 1
        this.facingFront = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
        this.sensorOrientation = info.orientation
        this.packageName = GlobalState.packageName
    }
    private fun CameraState.bindSurface(camera: Camera, surface: Surface) {
        val params = camera.parameters
        val pictureSize = params.pictureSize
        val previewSize = params.previewSize

        if (this.pictureWidth != pictureSize.width || this.pictureHeight != pictureSize.height) {
            this.pictureWidth = pictureSize.width
            this.pictureHeight = pictureSize.height
        }
        if (this.previewWidth != previewSize.width || this.previewHeight != previewSize.height) {
            this.previewWidth = previewSize.width
            this.previewHeight = previewSize.height
        }

        this.surface = surface
    }

    private lateinit var magic: MagicEntry
    fun initHooks(module: MagicEntry, param: PackageLoadedParam) {
        magic = module
        val classLoader = param.classLoader
        val cameraClass = classLoader.loadClass("android.hardware.Camera")

        val openMethods = cameraClass.declaredMethods.filter { it.name == "open" }
        openMethods.forEach { method ->
            module.hook(method, OpenMethodsHooker::class.java)
        }

        val setTexture = cameraClass.getDeclaredMethod(
            "setPreviewTexture",
            SurfaceTexture::class.java)
        module.hook(setTexture, SetPreviewTextureHooker::class.java)

        val setPreviewDisplay = cameraClass.getDeclaredMethod(
            "setPreviewDisplay",
            SurfaceHolder::class.java)
        module.hook(setPreviewDisplay, SetPreviewDisplayHooker::class.java)

        val setDisplayOrientation = cameraClass.getDeclaredMethod(
            "setDisplayOrientation",
            Int::class.javaPrimitiveType)
        module.hook(setDisplayOrientation, SetDisplayOrientationHooker::class.java)

        val startPreview = cameraClass.getDeclaredMethod("startPreview")
        module.hook(startPreview, StartPreviewHooker::class.java)

        val stopPreview = cameraClass.getDeclaredMethod("stopPreview")
        module.hook(stopPreview, StopPreviewHooker::class.java)

        val release = cameraClass.getDeclaredMethod("release")
        module.hook(release, ReleaseHooker::class.java)

        val setPreviewCallback = cameraClass.getDeclaredMethod(
            "setPreviewCallback",
            Camera.PreviewCallback::class.java)
        module.hook(setPreviewCallback, PreviewCallbackHooker::class.java)

        val setPreviewCallbackWithBuffer = cameraClass.getDeclaredMethod(
            "setPreviewCallbackWithBuffer",
            Camera.PreviewCallback::class.java)

        module.hook(setPreviewCallbackWithBuffer, PreviewCallbackHooker::class.java)

//        val addCallbackBufferMethod = cameraClass.getDeclaredMethod("addCallbackBuffer", ByteArray::class.java)
//        module.hook(addCallbackBufferMethod, CameraAddCallbackBuffer::class.java)

        val takePicture = cameraClass.getDeclaredMethod(
            "takePicture",
            Camera.ShutterCallback::class.java,
            Camera.PictureCallback::class.java, // raw
            Camera.PictureCallback::class.java, // post view
            Camera.PictureCallback::class.java) // jpeg

        module.hook(takePicture, TakePictureHooker::class.java)

    }
    class OpenMethodsHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun after(callback: AfterHookCallback) {
                val camera = callback.result as Camera
                activeCameraRef = WeakReference(camera)
                val cameraId = callback.args.getOrNull(0) as? Int ?: 0
                val info = Camera.CameraInfo()
                Camera.getCameraInfo(cameraId, info)
                val state = getCameraState(camera)
                state.saveCameraInfo(info)
            }
        }
    }

    class SetPreviewTextureHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                if (!SourceManager.isReadyForHook()) return
                val camera = callback.thisObject as Camera
                val st = callback.args[0] as SurfaceTexture
                surfaceCache[camera] = st
                callback.args[0] = SurfaceTexture(false)
                    .apply { setDefaultBufferSize(1, 1) }
                     .also { blackHole = it }
            }
        }
    }

    class SetPreviewDisplayHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                if (!SourceManager.isReadyForHook()) return
                val camera = callback.thisObject as Camera
                val holder = callback.args[0] as SurfaceHolder
                surfaceCache[camera] = holder.surface
                val surfaceTexture = SurfaceTexture(false)
                .apply { setDefaultBufferSize(1, 1) }
                callback.args[0] = Surface(surfaceTexture)
                    .also { blackHole = it }
            }
        }
    }

    class SetDisplayOrientationHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                if (!SourceManager.isReadyForHook()) return
                val camera = callback.thisObject as Camera
                val state = getCameraState(camera)
                val displayOrientation = callback.args[0] as Int
                if (state.displayOrientation == displayOrientation) return
                state.displayOrientation = displayOrientation
                if (isPreviewing(camera)) {
                    NativeBridge.setDisplayOrientation(displayOrientation)
                }
            }
        }
    }

    class StartPreviewHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                if (!SourceManager.isReadyForHook()) return
                val camera = callback.thisObject as Camera
                val surface = getSurfaceFrom(surfaceCache[camera]) ?: return
                val state = getCameraState(camera)
                state.bindSurface(camera, surface)
                val activeCamera = activeCameraRef?.get()
                if (activeCamera != null && camera === activeCamera) {
                    NativeBridge.registerSurfaceIfNew(state, true)
                    NativeBridge.needStartRenderer()
                }
            }
        }
    }

    class StopPreviewHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                val camera = callback.thisObject as Camera
                val activeCamera = activeCameraRef?.get()
                if (activeCamera != null && camera === activeCamera) {
                    NativeBridge.needStopRenderer()
                }
            }
        }
    }

    class ReleaseHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                val closingCamera = callback.thisObject as Camera
                val activeCamera = activeCameraRef?.get()

                if (activeCamera != null && closingCamera === activeCamera) {
                    NativeBridge.needStopRenderer()
                    NativeBridge.releaseLastRegisteredSurface()
                    destroyBlackHole()
                    activeCameraRef = null
                }
            }
        }
    }

    class PreviewCallbackHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                if (!SourceManager.isReadyForHook()) return
                val camera = callback.thisObject as Camera
                val originCallback = callback.args[0] as Camera.PreviewCallback
                val clazz = originCallback.javaClass
                val onPreviewFrame = clazz.getDeclaredMethod("onPreviewFrame",
                    ByteArray::class.java,
                    Camera::class.java)
                magic.hook(onPreviewFrame, OnPreviewFrameHooker::class.java)
            }
        }
    }

    class OnPreviewFrameHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                val originBuffer = callback.args[0] as ByteArray
                NativeBridge.overwritePreviewBuffer(originBuffer)
            }
        }
    }

    class CameraAddCallbackBuffer : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                if (!SourceManager.isReadyForHook()) return
            }
        }
    }

    class TakePictureHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                // 只hook jpeg拍照
                callback.args[3]?.let { cb ->
                    val clazz = (cb as Camera.PictureCallback).javaClass
                    val shot = clazz.getDeclaredMethod("onPictureTaken",
                        ByteArray::class.java, Camera::class.java)
                    magic.hook(shot, ShotJPEGHooker::class.java)
                }
            }
        }
    }

    class ShotJPEGHooker: XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                val jpegBytes = callback.args[0] as ByteArray
                NativeBridge.overwriteJPEGBytes(jpegBytes)
            }
        }
    }
}