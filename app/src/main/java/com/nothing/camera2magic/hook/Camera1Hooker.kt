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
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.BeforeHookCallback
import io.github.libxposed.api.XposedInterface.AfterHookCallback
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.WeakHashMap
object Camera1Hooker {
    private const val TAG = "[CAM1]"

    private val Camera?.shortId : String
        get() = if (this == null) "null" else "@0x${Integer.toHexString(System.identityHashCode(this))}"

    private var activeCameraRef: WeakReference<Any>? = null
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
    fun initHooks(module: MagicHook, param: PackageLoadedParam) {

        magic = module
        val classLoader = param.classLoader
        val cameraClass = classLoader.loadClass("android.hardware.Camera")

        val openMethods = cameraClass.declaredMethods.filter { it.name == "open" }
        openMethods.forEach { method ->
            module.hook(method, OpenMethodsHooker::class.java)
        }

        val setParameters = cameraClass.getDeclaredMethod(
            "setParameters",
            Camera.Parameters::class.java)
        module.hook(setParameters, CameraSetParameters::class.java)

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

        val addCallbackBuffer = cameraClass.getDeclaredMethod("addCallbackBuffer", ByteArray::class.java)
        module.hook(addCallbackBuffer, AddCallbackBufferHooker::class.java)

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

                state.apiLevel = 1
                state.facingFront = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
                state.sensorOrientation = info.orientation
                state.packageName = GlobalState.packageName
            }
        }
    }

    class CameraSetParameters: XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun after(callback: AfterHookCallback) {
                if (!SourceManager.isReadyForHook()) return
                val camera = callback.thisObject as Camera
                val params = callback.args[0] as Camera.Parameters
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
    }

    class SetPreviewTextureHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                if (!SourceManager.isReadyForHook()) return
                pushMode = false
                val camera = callback.thisObject as Camera
                val surfaceTexture = callback.args[0] as SurfaceTexture
                val state = getCameraState(camera)

                @SuppressLint("Recycle")
                state.surface = Surface(surfaceTexture)

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
                pushMode = true
                val camera = callback.thisObject as Camera
                val holder = callback.args[0] as SurfaceHolder
                val state = getCameraState(camera)
                state.surface = holder.surface

                @SuppressLint("Recycle")
                val surfaceTexture = SurfaceTexture(false)
                    .apply { setDefaultBufferSize(1, 1) }
                val surface = Surface(surfaceTexture).also { blackHole = it }
                val proxyHolder = Proxy.newProxyInstance(holder.javaClass.classLoader,
                    arrayOf(SurfaceHolder::class.java)) { _, method, args ->
                    if (method.name == "getSurface") return@newProxyInstance surface
                    return@newProxyInstance method.invoke(holder, *(args ?: arrayOfNulls<Any>(0)))
                } as SurfaceHolder
                callback.args[0] = proxyHolder
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
                val state = getCameraState(camera)
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
                if (!SourceManager.isReadyForHook()) return
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
                if (!SourceManager.isReadyForHook()) return
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
                val originCallback = callback.args[0] as? Camera.PreviewCallback ?: return
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

    class AddCallbackBufferHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                if (!SourceManager.isReadyForHook()) return
                val bytes = callback.args[0]
            }
        }
    }

    class TakePictureHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                if (!SourceManager.isReadyForHook()) return
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