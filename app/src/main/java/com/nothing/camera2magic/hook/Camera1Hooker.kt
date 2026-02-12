@file:Suppress("DEPRECATION")

package com.nothing.camera2magic.hook

import android.os.Handler
import android.os.Looper
import android.hardware.Camera
import android.view.Surface
import android.view.SurfaceHolder
import android.graphics.SurfaceTexture
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.MagicEntry
import com.nothing.camera2magic.utils.Dog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.BeforeHookCallback
import io.github.libxposed.api.XposedInterface.AfterHookCallback
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.ref.WeakReference
import java.util.WeakHashMap
object Camera1Hooker {
    private const val TAG = "[CAM1]"
    private var activeCameraRef: WeakReference<Any>? = null
    private var cameraState = WeakHashMap<Camera, CameraState>()
    private val surfaceCache = WeakHashMap<Camera, Any>()
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
    fun initHooks(module: MagicEntry, param: PackageLoadedParam) {
        val classLoader = param.classLoader
        val cameraClass = classLoader.loadClass("android.hardware.Camera")

        val openMethods = cameraClass.declaredMethods.filter { it.name == "open" }
        openMethods.forEach { method ->
            module.hook(method, CameraOpen::class.java)
        }

        val setParametersMethod = cameraClass.getDeclaredMethod("setParameters", Camera.Parameters::class.java)
        module.hook(setParametersMethod, CameraSetParameters::class.java)

        val setTextureMethod = cameraClass.getDeclaredMethod("setPreviewTexture", SurfaceTexture::class.java)
        module.hook(setTextureMethod, CameraSetTexture::class.java)

        val setPreviewDisplayMethod = cameraClass.getDeclaredMethod("setPreviewDisplay",
            SurfaceHolder::class.java)
        module.hook(setPreviewDisplayMethod, CameraSetPreviewDisplay::class.java)

        val setDisplayOrientationMethod = cameraClass.getDeclaredMethod("setDisplayOrientation", Int::class.javaPrimitiveType)
        module.hook(setDisplayOrientationMethod, CameraSetDisplayOrientation::class.java)

        val startPreviewMethod = cameraClass.getDeclaredMethod("startPreview")
        module.hook(startPreviewMethod, CameraStartPreview::class.java)

        val stopPreviewMethod = cameraClass.getDeclaredMethod("stopPreview")
        module.hook(stopPreviewMethod, CameraStopPreview::class.java)

        val releaseMethod = cameraClass.getDeclaredMethod("release")
        module.hook(releaseMethod, CameraRelease::class.java)

        val setPreviewCallbackMethod = cameraClass.getDeclaredMethod("setPreviewCallback", Camera.PreviewCallback::class.java)
        module.hook(setPreviewCallbackMethod, CameraSetPreviewCallback::class.java)

        val setPreviewCallbackWithBufferMethod = cameraClass.getDeclaredMethod("setPreviewCallbackWithBuffer", Camera.PreviewCallback::class.java)
        module.hook(setPreviewCallbackWithBufferMethod, CameraSetPreviewCallback::class.java)

        val addCallbackBufferMethod = cameraClass.getDeclaredMethod("addCallbackBuffer", ByteArray::class.java)
        module.hook(addCallbackBufferMethod, CameraAddCallbackBuffer::class.java)

        val takePictureMethod = cameraClass.getDeclaredMethod("takePicture",
            Camera.ShutterCallback::class.java,
            Camera.PictureCallback::class.java, // raw
            Camera.PictureCallback::class.java, // post view
            Camera.PictureCallback::class.java) // jpeg

        module.hook(takePictureMethod, CameraTakePicture::class.java)

    }

    class CameraOpen : XposedInterface.Hooker {
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
    class CameraSetTexture : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                if (!SourceManager.isReadyForHook()) return
                val camera = callback.thisObject as Camera
                val st = callback.args[0] as SurfaceTexture
                surfaceCache[camera] = st
            }
        }
    }
    class CameraSetPreviewDisplay : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                if (!SourceManager.isReadyForHook()) return
                val camera = callback.thisObject as Camera
                val holder = callback.args[0] as SurfaceHolder
                surfaceCache[camera] = holder.surface
            }
        }
    }
    class CameraSetDisplayOrientation : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                if (!SourceManager.isReadyForHook()) return
                val camera = callback.thisObject as Camera
                val state = getCameraState(camera)
                val displayOrientation = callback.args[0] as Int
                if (state.displayOrientation == displayOrientation) return
                state.displayOrientation = displayOrientation
                if (isPreviewing(camera)) NativeBridge.setDisplayOrientation(displayOrientation)
            }
        }
    }
    class CameraStartPreview : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                if (!SourceManager.isReadyForHook()) return
                val camera = callback.thisObject as Camera

                val state = getCameraState(camera)

                state.surface = getSurfaceFrom(surfaceCache[camera]) ?: return
                val activeCamera = activeCameraRef?.get()

                if (activeCamera != null && camera === activeCamera) {
                    NativeBridge.registerSurfaceIfNew(state, true)
                    NativeBridge.needStartRenderer()
                }
                callback.returnAndSkip(null)
            }
        }
    }
    class CameraStopPreview : XposedInterface.Hooker {
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
    class CameraRelease : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                val closingCamera = callback.thisObject as Camera
                val activeCamera = activeCameraRef?.get()

                if (activeCamera != null && closingCamera === activeCamera) {
                    NativeBridge.needStopRenderer()
                    NativeBridge.releaseLastRegisteredSurface()
                    activeCameraRef = null
                }
            }
        }
    }
    class CameraSetPreviewCallback : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                if (!SourceManager.isReadyForHook()) return
                val camera = callback.thisObject as Camera
                val originCallback = callback.args[0] as? Camera.PreviewCallback
                NativeBridge.camera1Callback = originCallback
                NativeBridge.currentCamera1 = camera
                 callback.returnAndSkip(null)
            }
        }
    }
    class CameraAddCallbackBuffer : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                if (!SourceManager.isReadyForHook()) return
                callback.returnAndSkip(null)
            }
        }
    }
    class CameraTakePicture : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                if (!SourceManager.isReadyForHook()) return
                val shutter = callback.args[0] as? Camera.ShutterCallback
                Handler(Looper.getMainLooper()).post { shutter?.onShutter() }

                val camera = callback.thisObject as Camera
                val state = getCameraState(camera)
                val jpegCallback = callback.args[3] as? Camera.PictureCallback
                if (jpegCallback != null) processShotJPEG(state.facingFront, jpegCallback, camera)

                callback.returnAndSkip(null)
            }

            private fun processShotJPEG(facingFront: Boolean, cb: Camera.PictureCallback, camera:Camera) {
                Thread {
                    NativeBridge.getFrameSnapshot()?.let { snapshot ->
                        val (data, w, h) = snapshot
                        val jpegData = NativeBridge.nv21ToJpegByteArray(facingFront, data, w, h)
                        // 回主线程回调
                        Handler(Looper.getMainLooper()).post { cb.onPictureTaken(jpegData, camera) }
                    }
                }.start()
            }
        }
    }
}