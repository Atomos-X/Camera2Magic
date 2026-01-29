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
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.BeforeHookCallback
import io.github.libxposed.api.XposedInterface.AfterHookCallback
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.ref.WeakReference
import java.util.WeakHashMap
object Camera1Hooker {
    private const val TAG = "[CAM1]"
    private const val BILIBILI = "tv.danmaku.bili"
    private var activeCameraRef: WeakReference<Any>? = null
    private var cameraState = WeakHashMap<Camera, CameraState>()
    private val surfaceCache = WeakHashMap<Camera, Any>()
    private fun getCameraState(camera: Camera): CameraState {
        return synchronized(cameraState) {
            cameraState.getOrPut(camera) {
                CameraState()
            }
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

        val openNoArgMethod = cameraClass.getDeclaredMethod("open")
        module.hook(openNoArgMethod, CameraOpen::class.java)

        val openWithArgMethod = cameraClass.getDeclaredMethod("open", Int::class.javaPrimitiveType)
        module.hook(openWithArgMethod, CameraOpen::class.java)

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

        val previewCallbackMethod = cameraClass.getDeclaredMethod("setPreviewCallback", Camera.PreviewCallback::class.java)
        module.hook(previewCallbackMethod, CameraSetPreviewCallback::class.java)

        val setPreviewCallbackWithBufferMethod = cameraClass.getDeclaredMethod("setPreviewCallbackWithBuffer", Camera.PreviewCallback::class.java)
        module.hook(setPreviewCallbackWithBufferMethod, CameraSetPreviewCallback::class.java)

        val addCallbackBufferMethod = cameraClass.getDeclaredMethod("addCallbackBuffer", ByteArray::class.java)
        module.hook(addCallbackBufferMethod, CameraAddCallbackBuffer::class.java)

        val takePictureMethod = cameraClass.getDeclaredMethod("takePicture",
            Camera.ShutterCallback::class.java,
            Camera.PictureCallback::class.java,
            Camera.PictureCallback::class.java,
            Camera.PictureCallback::class.java)

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
                state.cameraId = cameraId
                state.isFrontCamera = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
                state.packageName = GlobalState.packageName ?: "UNKNOWN"
            }
        }
    }
    class CameraSetParameters: XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun after(callback: AfterHookCallback) {
                if (!MagicNative.isReadyForHook()) return
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
                if (!MagicNative.isReadyForHook()) return
                val camera = callback.thisObject as Camera
                val st = callback.args[0] as SurfaceTexture
                surfaceCache[camera] = st
                /**
                 * @brief 对于 bilibili 的特殊处理
                 * SurfaceTexture bufferSize = (1,1) -> ANativeWindow bufferSize
                 * dirty fix: SurfaceTexture bufferSize = (1920,1080) -> ANativeWindow bufferSize
                 */
                val state = getCameraState(camera)
                if (GlobalState.packageName == BILIBILI) st.setDefaultBufferSize(state.previewWidth, state.previewHeight)
            }
        }
    }
    class CameraSetPreviewDisplay : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                if (!MagicNative.isReadyForHook()) return
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
                if (!MagicNative.isReadyForHook()) return
                val camera = callback.thisObject as Camera
                val state = getCameraState(camera)
                val displayOrientation = callback.args[0] as Int
                if (state.displayOrientation == displayOrientation) return
                state.displayOrientation = displayOrientation
                if (isPreviewing(camera)) MagicNative.setDisplayOrientation(displayOrientation)
            }
        }
    }
    class CameraStartPreview : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                if (!MagicNative.isReadyForHook()) return
                val camera = callback.thisObject as Camera

                val state = getCameraState(camera)

                state.surface = getSurfaceFrom(surfaceCache[camera]) ?: return
                val info = Camera.CameraInfo()
                Camera.getCameraInfo(state.cameraId.toInt(), info)
                state.sensorOrientation = info.orientation
                val activeCamera = activeCameraRef?.get()

                if (activeCamera != null && camera === activeCamera) {
                    MagicNative.registerSurfaceIfNew(state, true)
                    MagicNative.needStartRenderer()
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
                    MagicNative.needStopRenderer()
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
                    MagicNative.needStopRenderer()
                    MagicNative.releaseLastRegisteredSurface()
                    activeCameraRef = null
                }
            }
        }
    }
    class CameraSetPreviewCallback : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                if (!MagicNative.isReadyForHook()) return
                MagicNative.camera1Callback = callback.args[0] as? Camera.PreviewCallback
                MagicNative.currentCamera1 = callback.thisObject as? Camera
                callback.returnAndSkip(null)
            }
        }
    }
    class CameraAddCallbackBuffer : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                if (!MagicNative.isReadyForHook()) return
                callback.returnAndSkip(null)
            }
        }
    }
    class CameraTakePicture : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                if (!MagicNative.isReadyForHook()) return
                callback.returnAndSkip(null)
                val camera = callback.thisObject as Camera
                val jpegCallback = callback.args[3] as? Camera.PictureCallback
                if (jpegCallback != null) {
                    Thread {
                        val snapshot = MagicNative.getFrameSnapshot()
                        if (snapshot != null) {
                            val (data, w, h) = snapshot
                            val jpegData = MagicNative.nv21ToJpegByteArray(data, w, h) ?: return@Thread
                            // 回主线程回调
                            Handler(Looper.getMainLooper()).post {
                                jpegCallback.onPictureTaken(jpegData, camera)
                            }
                        }
                    }.start()
                }
            }
        }
    }
}