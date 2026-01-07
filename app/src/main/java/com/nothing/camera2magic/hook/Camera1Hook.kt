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
import com.nothing.camera2magic.hook.MagicNative.getApiLevel
import com.nothing.camera2magic.hook.MagicNative.logDog as DOG
import com.nothing.camera2magic.hook.MagicNative.setDisplayOrientation
import com.nothing.camera2magic.hook.MagicNative.updateCameraParameters
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.io.ByteArrayOutputStream

private const val TAG = "[CAM1]"
fun camera1Hook(lpparam: LoadPackageParam, magicEntry: MagicEntry, surfaceTextureCache: MutableMap<SurfaceTexture, Surface>) {
    try {
        val cameraClass = XposedHelpers.findClass("android.hardware.Camera", lpparam.classLoader)

        val captureHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!MagicNative.moduleEnabled) return
                if (!MagicNative.videoSourceIsReady) return
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
                surface?.let { magicEntry.registerSurfaceIfNew(it) }
            }
        }

        XposedBridge.hookAllMethods(cameraClass, "open", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!MagicNative.moduleEnabled) return
                if (!MagicNative.videoSourceIsReady) return
                getApiLevel(1)
                val cameraId = if (param.args.isNotEmpty() && param.args[0] is Int) {
                    (param.args[0] as Int).toString()
                } else {
                    "0"
                }

                val cameraObj = param.result as? Camera ?: return

                XposedHelpers.setAdditionalInstanceField(cameraObj, "magic_camera_id", cameraId)

                var sensorOrientation = 0
                try {
                    val camIdInt = cameraId.toIntOrNull()
                    if (camIdInt != null) {
                        val info = Camera.CameraInfo()
                        Camera.getCameraInfo(camIdInt, info)
                        sensorOrientation = info.orientation
                    }
                } catch (_: Throwable) { }

                XposedHelpers.setAdditionalInstanceField(cameraObj, "magic_sensor_ori", sensorOrientation)

                try {
                    val params = cameraObj.parameters
                    val size = params.previewSize ?: params.pictureSize
                    updateCameraParameters(cameraId, sensorOrientation, size.width, size.height)
                } catch (t: Throwable) {
                    DOG(TAG, "Failed to fetch default params: ${t.message}", MagicNative.enableLog)
                }
            }
        })

        XposedHelpers.findAndHookMethod(cameraClass, "setParameters", Camera.Parameters::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!MagicNative.moduleEnabled) return
                if (!MagicNative.videoSourceIsReady) return
                val cameraObj = param.thisObject as? Camera ?: return
                val cameraId = XposedHelpers.getAdditionalInstanceField(cameraObj, "magic_camera_id") as? String ?: return
                val sensorOrientation = XposedHelpers.getAdditionalInstanceField(cameraObj, "magic_sensor_ori") as? Int ?: 0
                val params = param.args[0] as Camera.Parameters
                val size = params.previewSize ?: params.pictureSize

                if (size != null) {
                    DOG(TAG, "App set params: ${size.width}x${size.height} for ID $cameraId",
                        MagicNative.enableLog)
                    updateCameraParameters(cameraId, sensorOrientation, size.width, size.height)
                }
            }
        })

        XposedHelpers.findAndHookMethod(cameraClass, "setPreviewTexture", SurfaceTexture::class.java, captureHook)
        XposedHelpers.findAndHookMethod(cameraClass, "setPreviewDisplay", SurfaceHolder::class.java, captureHook)

        XposedHelpers.findAndHookMethod(cameraClass, "setDisplayOrientation", Int::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!MagicNative.moduleEnabled) return
                if (!MagicNative.videoSourceIsReady) return
                val orientation = param.args[0] as Int
                setDisplayOrientation(orientation)
            }
        })

        XposedHelpers.findAndHookMethod(cameraClass, "startPreview", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!MagicNative.moduleEnabled) return
                if (!MagicNative.videoSourceIsReady) return
                param.result = null
            }
        })
        XposedHelpers.findAndHookMethod(cameraClass, "setPreviewCallback", Camera.PreviewCallback::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!MagicNative.moduleEnabled) return
                if (!MagicNative.videoSourceIsReady) return
                MagicNative.camera1Callback = param.args[0] as? Camera.PreviewCallback
                MagicNative.currentCamera1 = param.thisObject as? Camera

                param.result = null
            }
        })
        XposedHelpers.findAndHookMethod(cameraClass, "setPreviewCallbackWithBuffer", Camera.PreviewCallback::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!MagicNative.moduleEnabled) return
                if (!MagicNative.videoSourceIsReady) return
                MagicNative.camera1Callback = param.args[0] as? Camera.PreviewCallback
                MagicNative.currentCamera1 = param.thisObject as? Camera
                param.result = null
            }
        })
        XposedHelpers.findAndHookMethod(cameraClass, "addCallbackBuffer", ByteArray::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!MagicNative.moduleEnabled) return
                if (!MagicNative.videoSourceIsReady) return
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
                    if (!MagicNative.moduleEnabled) return
                    if (!MagicNative.videoSourceIsReady) return
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
                                    DOG(TAG, "Compress failed: ${e.message}", MagicNative.enableLog)
                                    ByteArray(0)
                                }
                            } else {
                                DOG(TAG, "Capture failed: Buffer invalid/empty after retries",
                                    MagicNative.enableLog)
                                ByteArray(0)
                            }

                            // 回主线程回调
                            Handler(Looper.getMainLooper()).post {
                                try {
                                    jpegCallback.onPictureTaken(jpegData, cameraObj)
                                } catch (e: Exception) {
                                    DOG(TAG, "Callback failed: ${e.message}", MagicNative.enableLog)
                                }
                            }
                        }.start()
                    }
                }
            })
        XposedHelpers.findAndHookMethod(cameraClass, "setPreviewFormat", Int::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!MagicNative.moduleEnabled) return
                if (!MagicNative.videoSourceIsReady) return
                val format = param.args[0] as Int
                // 如果 App 尝试设置非 NV21 (ImageFormat.NV21 = 17)，我们可以在这里记录日志
                if (format != 17) {
                    DOG(TAG, "App requested non-NV21 format: $format. Forcing NV21.", MagicNative.enableLog)
                    param.args[0] = 17
                }
                param.result = null
            }
        })


    } catch (t: Throwable) {
        DOG(TAG,"Couldn't hook Camera1: ${t.message}", MagicNative.enableLog)
    }
}