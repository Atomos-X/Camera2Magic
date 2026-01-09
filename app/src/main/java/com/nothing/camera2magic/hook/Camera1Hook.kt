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
import com.nothing.camera2magic.MagicEntry
import com.nothing.camera2magic.utils.Dog
import com.nothing.camera2magic.hook.MagicNative.updateCameraParameters
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.WeakHashMap

private const val TAG = "[CAM1]"

private var isNativeEnvInitialized = false
private var activeCameraRef: WeakReference<Any>? = null
private var cameraState = WeakHashMap<Camera, CameraState>()
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

fun camera1Hook(lpparam: LoadPackageParam, magicEntry: MagicEntry, surfaceTextureCache: MutableMap<SurfaceTexture, Surface>) {

    val cameraClass = XposedHelpers.findClass("android.hardware.Camera", lpparam.classLoader)

    val openHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam?) {
            if (!MagicNative.moduleEnabled) return
            if (!MagicNative.videoSourceIsReady) return
            val cameraObject = param?.result as? Camera ?: return

            if (!isNativeEnvInitialized) {
                MagicNative.getApiLevel(1)
                isNativeEnvInitialized = true
            }

            activeCameraRef = WeakReference(cameraObject)

            var cameraId = "0"
            var sensorOrientation = 0

            if (param.args?.isNotEmpty() == true && param.args[0] is Int) {
               cameraId = (param.args[0] as Int).toString()
            }

            try {
                val camIdInt = cameraId.toIntOrNull()
                if (camIdInt != null) {
                    val info = Camera.CameraInfo()
                    Camera.getCameraInfo(camIdInt, info)
                    sensorOrientation = info.orientation
                }
            } catch (_: Throwable) { }

            val state = getCameraState(cameraObject)

            state.cameraId = cameraId
            state.sensorOrientation = sensorOrientation


            try {
                val params = cameraObject.parameters
                val pictureSize = params.pictureSize
                if (pictureSize != null) {
                    state.pictureWidth = pictureSize.width
                    state.pictureHeight = pictureSize.height
                }
            } catch (t: Throwable) {
                Dog.i(TAG, "Failed to fetch default params: ${t.message}", MagicNative.enableLog)
            }
        }
    }

    val captureHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (!MagicNative.isReadyForHook()) return
            var surface: Surface? = null
            val matrix = FloatArray(16)
            when (val arg = param.args[0]) {
                is SurfaceHolder -> surface = arg.surface
                is SurfaceTexture -> {
                    arg.getTransformMatrix(matrix)
                    synchronized(surfaceTextureCache) {
                        val cached = surfaceTextureCache[arg]
                        if (cached != null) {
                            surface = cached
                        } else {
                            val s = Surface(arg)
                            surfaceTextureCache[arg] = s
                            surface = s
                        }
                    }
                }
            }
            MagicNative.updateExternalMatrix(matrix)
            surface?.let { surface ->
                val state = getCameraState(param.thisObject as Camera)
                magicEntry.registerSurfaceIfNew(surface, state.displayOrientation)
            }
        }
    }

    XposedBridge.hookAllMethods(cameraClass, "open", openHook)
    XposedHelpers.findAndHookMethod(cameraClass, "open", Int::class.javaPrimitiveType, openHook)

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
            if (isPreviewing(cameraObject)) {
                updateCameraParameters(state.cameraId, state.sensorOrientation, state.pictureWidth, state.pictureHeight)
            }
        }
    })

    XposedHelpers.findAndHookMethod(cameraClass, "setPreviewTexture", SurfaceTexture::class.java, captureHook)
    XposedHelpers.findAndHookMethod(cameraClass, "setPreviewDisplay", SurfaceHolder::class.java, captureHook)

    XposedHelpers.findAndHookMethod(cameraClass, "setDisplayOrientation", Int::class.java, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (!MagicNative.isReadyForHook()) return
            val cameraObject = param.thisObject as? Camera ?: return

            val state = getCameraState(cameraObject)
            state.displayOrientation = param.args[0] as Int
        }
    })

    XposedHelpers.findAndHookMethod(cameraClass, "startPreview", object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (!MagicNative.isReadyForHook()) return
            val cameraObject = param.thisObject as Camera
            val activeCamera = activeCameraRef?.get()
            if (activeCamera != null && cameraObject === activeCamera) {
                MagicNative.needStartRenderer()
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
        override fun beforeHookedMethod(param: MethodHookParam?) {
            val camera = param?.thisObject as? Camera ?:return
            val activeCamera = activeCameraRef?.get()
            if (activeCamera != null && camera === activeCamera) {
                MagicNative.needStopRenderer()
            }
        }
    })

    XposedHelpers.findAndHookMethod(cameraClass, "release", object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val closingCamera = param.thisObject
            val activeCamera = activeCameraRef?.get()

            if (activeCamera != null && closingCamera === activeCamera) {
                val hash = System.identityHashCode(closingCamera).toString(16)
                Dog.i(TAG, "+++ Camera Closed! Hash=@$hash", MagicNative.enableLog)
                MagicNative.needStopRenderer()
                activeCameraRef = null
            } else {
                Dog.i(TAG, "Ignored stale release !", MagicNative.enableLog)
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