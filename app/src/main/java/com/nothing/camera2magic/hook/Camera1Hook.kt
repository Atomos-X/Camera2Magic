@file:Suppress("DEPRECATION")
package com.nothing.camera2magic.hook

import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import com.nothing.camera2magic.hook.MagicNative.needStartRenderer
import com.nothing.camera2magic.hook.MagicNative.needStopRenderer
import com.nothing.camera2magic.hook.MagicNative.registerSurfaceIfNew
import com.nothing.camera2magic.hook.MagicNative.releaseLastRegisteredSurface
import com.nothing.camera2magic.hook.MagicNative.setDisplayOrientation
import com.nothing.camera2magic.utils.Dog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import java.util.WeakHashMap

private const val TAG = "[CAM1]"

// dirty fix
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

fun camera1Hook(lpparam: LoadPackageParam) {

    val pkg = lpparam.packageName
    val cameraClass = XposedHelpers.findClass("android.hardware.Camera", lpparam.classLoader)

    XposedHelpers.findAndHookMethod(cameraClass, "open", Int::class.javaPrimitiveType, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val camera = param.result as? Camera ?: return
            activeCameraRef = WeakReference(camera)
            val state = getCameraState(camera)
            state.apiLevel = 1
            state.cameraId = (param.args[0] as Int).toString()
            state.packageName = pkg
        }
    })

    XposedHelpers.findAndHookMethod(cameraClass, "setParameters", Camera.Parameters::class.java, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (!MagicNative.isReadyForHook()) return
            val camera = param.thisObject as Camera
            val params = param.args[0] as Camera.Parameters
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
    })


    XposedHelpers.findAndHookMethod(cameraClass, "setPreviewTexture", SurfaceTexture::class.java, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (!MagicNative.isReadyForHook()) return
            val camera = param.thisObject as Camera
            val st = param.args[0] as SurfaceTexture
            surfaceCache[camera] = st
            /**
             * @brief 对于 bilibili 的特殊处理
             * SurfaceTexture bufferSize = (1,1) -> ANativeWindow bufferSize
             * dirty fix: SurfaceTexture bufferSize = (1920,1080) -> ANativeWindow bufferSize
             */
            val state = getCameraState(camera)
            if (pkg == BILIBILI) st.setDefaultBufferSize(state.previewWidth, state.previewHeight)
        }
    })

    XposedHelpers.findAndHookMethod(cameraClass, "setPreviewDisplay", SurfaceHolder::class.java, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (!MagicNative.isReadyForHook()) return
            val camera = param.thisObject as Camera
            val holder = param.args[0] as SurfaceHolder
            surfaceCache[camera] = holder.surface
        }
    })

    XposedHelpers.findAndHookMethod(cameraClass, "setDisplayOrientation", Int::class.java, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (!MagicNative.isReadyForHook()) return
            val camera = param.thisObject as Camera
            val state = getCameraState(camera)
            val ori = param.args[0] as Int
            if (state.displayOrientation == ori) return
            state.displayOrientation = ori
            if (isPreviewing(camera)) setDisplayOrientation(ori)
        }
    })

    XposedHelpers.findAndHookMethod(cameraClass, "startPreview", object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (!MagicNative.isReadyForHook()) return
            val camera = param.thisObject as Camera

            val state = getCameraState(camera)

            state.surface = getSurfaceFrom(surfaceCache[camera]) ?: return

            val info = Camera.CameraInfo()
            Camera.getCameraInfo(state.cameraId.toInt(), info)
            state.sensorOrientation = info.orientation

            val activeCamera = activeCameraRef?.get()

            if (activeCamera != null && camera === activeCamera) {
                registerSurfaceIfNew(state, true)
                needStartRenderer()
            }

            param.result = null
        }
    })

    XposedHelpers.findAndHookMethod(cameraClass, "setPreviewCallback", Camera.PreviewCallback::class.java, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (!MagicNative.isReadyForHook()) return
            MagicNative.camera1Callback = param.args[0] as? Camera.PreviewCallback
            MagicNative.currentCamera1 = param.thisObject as? Camera

            param.result = null
        }
    })

    XposedHelpers.findAndHookMethod(cameraClass, "stopPreview", object: XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val camera = param.thisObject as Camera
            val activeCamera = activeCameraRef?.get()
            if (activeCamera != null && camera === activeCamera) {
                needStopRenderer()
            }
        }
    })

    XposedHelpers.findAndHookMethod(cameraClass, "release", object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val closingCamera = param.thisObject as Camera
            val activeCamera = activeCameraRef?.get()

            if (activeCamera != null && closingCamera === activeCamera) {
                needStopRenderer()
                releaseLastRegisteredSurface()
                activeCameraRef = null
            }
        }
    })

    XposedHelpers.findAndHookMethod(cameraClass, "setPreviewCallbackWithBuffer", Camera.PreviewCallback::class.java, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (!MagicNative.isReadyForHook()) return
            MagicNative.camera1Callback = param.args[0] as? Camera.PreviewCallback
            MagicNative.currentCamera1 = param.thisObject as? Camera
            param.result = null
        }
    })
    XposedHelpers.findAndHookMethod(cameraClass, "addCallbackBuffer", ByteArray::class.java, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (!MagicNative.isReadyForHook()) return
            param.result = null
        }
    })

    XposedHelpers.findAndHookMethod(cameraClass, "takePicture",
        Camera.ShutterCallback::class.java,
        Camera.PictureCallback::class.java,
        Camera.PictureCallback::class.java,
        Camera.PictureCallback::class.java,
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!MagicNative.isReadyForHook()) return
                param.result = null
                val camera = param.thisObject as Camera
                val jpegCallback = param.args[3] as? Camera.PictureCallback

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
        })
}