@file:Suppress("DEPRECATION")

package com.nothing.camera2magic.hook

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.Camera
import android.view.Surface
import android.view.SurfaceHolder
import android.graphics.SurfaceTexture
import android.os.IBinder
import android.provider.Settings
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.MagicHook
import com.nothing.camera2magic.utils.Dog
import com.nothing.camera2magic.hook.NativeBridge as NB
import com.nothing.camera2magic.hook.SourceManager as SM
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

@SuppressLint("Recycle")
class Camera1Hooker(val magic: MagicHook, param: PackageReadyParam) : HookManager  {
    companion object {
        private const val TAG = "[CAM1]"
        private const val API: Int = 1
        private const val CLS_CAMERA = "android.hardware.Camera"
        private var activatedCamera = WeakReference<Any>(null)
        private var facingFront: Boolean = false
        private var sensorOri: Int = 0
        private var displayOri: Int = 0
        private var originSurface: Surface? = null
        private lateinit var pictureSize: Camera.Size
        private lateinit var previewSize: Camera.Size
        private val Camera.isActiveRef: Boolean
            get() = activatedCamera.get() == this
    }

    private val openInterceptor: (Chain) -> Any? = intercept@{ chain ->
        val camera = chain.proceed() as? Camera ?: return@intercept null
        val cameraId = chain.args.getOrNull(0) as? Int ?: 0
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(cameraId, info)
        activatedCamera = WeakReference(camera)
        facingFront = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
        sensorOri = info.orientation
        Dog.i(TAG, "${camera.shortId} open.", SM.enableLog)
        return@intercept camera
    }

    private val previewCallbackInterceptor: (Chain) -> Any? = intercept@ { chain ->
        if (!SM.readyForHook) return@intercept chain.proceed()
        val originCallback = chain.args[0] as? Camera.PreviewCallback ?: return@intercept chain.proceed()
        originCallback.javaClass.safeHook { onPreviewFrameHook() }
        chain.proceed()
    }

    override val hookedClasses: MutableSet<Class<*>> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    init {
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
            val params = chain.args[0] as Camera.Parameters
            pictureSize = params.pictureSize
            previewSize = params.previewSize
            return@intercept chain.proceed()
        }
    }
    private fun Class<*>.setPreviewTextureHook() {
        val setPreviewTexture = getDeclaredMethod("setPreviewTexture",
            SurfaceTexture::class.java)
        magic.hook(setPreviewTexture).intercept { chain ->
            if (!SM.readyForHook) return@intercept chain.proceed()
            val surfaceTexture = chain.args[0] as SurfaceTexture
            @SuppressLint("Recycle")
            originSurface = Surface(surfaceTexture)

            val newArgs = chain.args.toTypedArray()
            newArgs[0] = BlackHole.surfaceTexture
            chain.proceed(newArgs)
        }
    }
    private fun Class<*>.setPreviewDisplayHook() {
        val setPreviewDisplay = getDeclaredMethod(
            "setPreviewDisplay",
            SurfaceHolder::class.java)
        magic.hook(setPreviewDisplay).intercept { chain ->
            if (!SM.readyForHook) return@intercept chain.proceed()
            val holder = chain.args[0] as SurfaceHolder
            originSurface = holder.surface
            @SuppressLint("Recycle")

            val surfaceHolderProxy = Proxy.newProxyInstance(holder.javaClass.classLoader,
                arrayOf(SurfaceHolder::class.java)) { _, method, args ->
                if (method.name == "getSurface") return@newProxyInstance BlackHole.surface
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
            if (!SM.readyForHook || displayOri == displayOrientation) return@intercept chain.proceed()
            displayOri = displayOrientation
            if (camera.isActiveRef) NB.setDisplayOrientation(displayOrientation)
            chain.proceed()
        }
    }

    @OptIn(UnstableApi::class)
    private fun Class<*>.startPreviewHook() {
        val startPreview = getDeclaredMethod("startPreview")
        magic.hook(startPreview).intercept { chain ->
            if (!SM.readyForHook) return@intercept chain.proceed()
            val camera = chain.thisObject as Camera
            if (camera.isActiveRef) {
                NB.updateCameraBaseData(API, facingFront, sensorOri, displayOri)
                NB.updateCameraExtendedData(originSurface!!, true,
                    previewSize.width, previewSize.height,
                    pictureSize.width, pictureSize.height)
//                SM.validMedia?.let { Camera3.start(it) }
                val pfd = magic.openRemoteFile("video.mp4")


            }
            chain.proceed()
        }
    }
    private fun Class<*>.stopPreviewHook() {
        val stopPreview = getDeclaredMethod("stopPreview")
        magic.hook(stopPreview).intercept { chain ->
            if (!SM.readyForHook) return@intercept chain.proceed()
            val camera = chain.thisObject as Camera
            if (camera.isActiveRef) Camera3.pause()
            chain.proceed()
        }
    }
    private fun Class<*>.releaseHook() {
        val release = getDeclaredMethod("release")
        magic.hook(release).intercept { chain ->
            if (!SM.readyForHook) return@intercept chain.proceed()
            val camera = chain.thisObject as Camera
            if (camera.isActiveRef) Camera3.stop()
            Dog.i(TAG, "${camera.shortId} close.", SM.enableLog)
            chain.proceed()
        }
    }
    private fun Class<*>.onPreviewFrameHook() {
        val onPreviewFrame = getDeclaredMethod("onPreviewFrame",
            ByteArray::class.java, Camera::class.java)
        magic.hook(onPreviewFrame).intercept { frame ->
            if (!SM.readyForHook) return@intercept frame.proceed()
            val originBuffer = frame.args[0] as ByteArray
            NB.overwriteYuvBuffer(originBuffer)
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
        val setOneShotPreviewCallback = getDeclaredMethod("setOneShotPreviewCallback",
            Camera.PreviewCallback::class.java)
        magic.hook(setPreviewCallback).intercept(previewCallbackInterceptor)
        magic.hook(setPreviewCallbackWithBuffer).intercept(previewCallbackInterceptor)
        magic.hook(setOneShotPreviewCallback).intercept(previewCallbackInterceptor)
    }
    private fun Class<*>.onPictureTakenHook() {
        val onPictureTaken = getDeclaredMethod("onPictureTaken",
            ByteArray::class.java, Camera::class.java)
        magic.hook(onPictureTaken).intercept { shot ->
            if (!SM.readyForHook) return@intercept shot.proceed()
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
            if (!SM.readyForHook) return@intercept chain.proceed()
            chain.args[3]?.let { cb ->
                val clazz = (cb as Camera.PictureCallback).javaClass
                clazz.safeHook { onPictureTakenHook() }
            }
            chain.proceed()
        }
    }
}