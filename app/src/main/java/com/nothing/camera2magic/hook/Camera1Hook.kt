@file:Suppress("DEPRECATION")
package com.nothing.camera2magic.hook

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
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
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.WeakHashMap

private const val TAG = "[CAM1]"
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

    val cameraClass = XposedHelpers.findClass("android.hardware.Camera", lpparam.classLoader)

    XposedHelpers.findAndHookMethod(cameraClass, "open", Int::class.javaPrimitiveType, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val camera = param.result as? Camera ?: return
            activeCameraRef = WeakReference(camera)
            val state = getCameraState(camera)
            state.apiLevel = 1
            state.cameraId = (param.args[0] as Int).toString()
        }
    })

    XposedHelpers.findAndHookMethod(cameraClass, "setParameters", Camera.Parameters::class.java, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (!MagicNative.isReadyForHook()) return
            val cameraObject = param.thisObject as Camera

            val params = param.args[0] as Camera.Parameters
            val pictureSize = params.pictureSize
            val state = getCameraState(cameraObject)

            if(state.pictureWidth != pictureSize.width || state.pictureHeight != pictureSize.height) {
                state.pictureWidth = pictureSize.width
                state.pictureHeight = pictureSize.height
            }
        }
    })

    XposedHelpers.findAndHookMethod(cameraClass, "setPreviewTexture", SurfaceTexture::class.java, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (!MagicNative.isReadyForHook()) return
            val camera = param.thisObject as Camera
            val st = param.args[0] as SurfaceTexture
            surfaceCache[camera] = st
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
            if (state.displayOrientation == ori) return;
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

    // a test of takePicture, will be rewritten
    XposedHelpers.findAndHookMethod(cameraClass, "takePicture",
        Camera.ShutterCallback::class.java,
        Camera.PictureCallback::class.java,
        Camera.PictureCallback::class.java,
        Camera.PictureCallback::class.java,
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!MagicNative.isReadyForHook()) return
                param.result = null

                val jpegCallback = param.args[3] as? Camera.PictureCallback
                val cameraObj = param.thisObject as? Camera

                if (jpegCallback != null) {
                    Thread {
                        // 简单的重试机制：如果正好赶上 Buffer 初始化（无效），稍微等一下
                        var snapshot: Triple<ByteArray, Int, Int>? = null
                        for (i in 0..5) {
                            snapshot = MagicNative.getFrameSnapshot()
                            if (snapshot != null) break
                            Thread.sleep(30)
                        }

                        val jpegData = if (snapshot != null) {
                            val (buffer, w, h) = snapshot
                            try {
                                val yuvImage = YuvImage(buffer, ImageFormat.NV21, w, h, null)
                                val outStream = ByteArrayOutputStream()
                                yuvImage.compressToJpeg(Rect(0, 0, w, h), 90, outStream)
                                outStream.toByteArray()
                            } catch (e: Exception) {
                                Dog.e(TAG, "Compress failed: ${e.message}", null, MagicNative.enableLog)
                                ByteArray(0)
                            }
                        } else {
                            Dog.i(TAG, "Capture failed: Buffer invalid/empty after retries", MagicNative.enableLog)
                            ByteArray(0)
                        }

                        // 回主线程回调
                        Handler(Looper.getMainLooper()).post {
                            try {
                                jpegCallback.onPictureTaken(jpegData, cameraObj)
                            } catch (e: Exception) {
                                Dog.e(TAG, "Callback failed: ${e.message}", null, MagicNative.enableLog)
                            }
                        }
                    }.start()
                }
            }
        })
}