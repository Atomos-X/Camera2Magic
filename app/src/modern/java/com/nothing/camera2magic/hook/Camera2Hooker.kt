package com.nothing.camera2magic.hook

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Camera
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.material3.NavigationBar
import androidx.media3.common.util.UnstableApi
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.MagicHook
import com.nothing.camera2magic.hook.SourceManager as SM
import com.nothing.camera2magic.hook.NativeBridge as NB
import com.nothing.camera2magic.utils.Dog
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

@SuppressLint("PrivateApi")
class Camera2Hooker(val magic: MagicHook, param: PackageReadyParam) : HookManager {

    override val hookedClasses: MutableSet<Class<*>> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    companion object {
        private const val TAG = "[CAM2]"
        private const val CAMERA_DEVICE_IMPL = "android.hardware.camera2.impl.CameraDeviceImpl"
        private const val CAPTURE_REQUEST_BUILDER = $$"android.hardware.camera2.CaptureRequest$Builder"
        private var activatedCamera = WeakReference<Any>(null)
        private var _cameraState = WeakHashMap<Any, CameraState>()

        private val CameraDevice.state: CameraState
            get() = _cameraState.getOrPut(this) { CameraState(api = 2) }

        private val CameraDevice.isActiveRef: Boolean
            get() = activatedCamera.get() == this

        private var originSurface = WeakReference<Surface>(null)
    }

    init {
        val classLoader = param.classLoader
        classLoader.safeHook(CAMERA_DEVICE_IMPL) {
            createCaptureSessionWithConfigurationHook()
            createCaptureSessionWithSurfacesHook()
            closeHook()
        }

        classLoader.safeHook(CAPTURE_REQUEST_BUILDER) {
            addTargetHook()
            removeTargetHook()
        }
    }


    private fun CameraDevice.updateBaseData() {
        val cameraId = this.id
        val context = GlobalState.appContext
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = cm.getCameraCharacteristics(cameraId)

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val rotation = wm.defaultDisplay.rotation
        val sensorOri = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val facingFront = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val displayOri = rotation * 90
        NB.updateCameraBaseData(2, facingFront, sensorOri, displayOri)
        activatedCamera = WeakReference(this)
    }

    @OptIn(UnstableApi::class)
    private fun Class<*>.onConfiguredHook() {
        val onConfigured = getDeclaredMethod("onConfigured",
            CameraCaptureSession::class.java)

        magic.hook(onConfigured).intercept { chain ->
            SM.validMedia?.let { Camera3.start(magic, it) }
            return@intercept chain.proceed()
        }
    }
    private fun Class<*>.onConfigureFailedHook() {
        val onConfigureFailed = getDeclaredMethod("onConfigureFailed",
            CameraCaptureSession::class.java)

        magic.hook(onConfigureFailed).intercept { chain ->
            Dog.e(TAG, "CameraCaptureSession.StateCallback: onConfigureFailed.", null, true)
            chain.proceed()
        }
    }
    private fun handleStateCallback(callback: CameraCaptureSession.StateCallback) {
        callback.javaClass.safeHook {
            onConfiguredHook()
            onConfigureFailedHook()
        }
    }
    private fun Class<*>.createCaptureSessionWithConfigurationHook() {
        val createCaptureSession = getDeclaredMethod(
            "createCaptureSession",
            SessionConfiguration::class.java)
        magic.hook(createCaptureSession).intercept { chain ->
            if (!SM.readyForHook) return@intercept chain.proceed()
            val camera = chain.thisObject as CameraDevice
            camera.updateBaseData()

            val sessionConfiguration = chain.args[0] as SessionConfiguration

            @SuppressLint("SoonBlockedPrivateApi")
            val field = OutputConfiguration::class.java.getDeclaredField("mSurfaces")
            field.isAccessible = true
            sessionConfiguration.outputConfigurations.forEach { outputConfiguration ->
                var modified = false
                val surfaces = outputConfiguration.surfaces
                val targetSurface = surfaces.find { NB.getSurfaceInfo(it)[3] == 1 } // 优先寻找 format=1
                    ?: surfaces.find { NB.getSurfaceInfo(it)[3] == 4 } // 找不到就找format = 4

                val modifiedSurfaces = surfaces.mapTo(ArrayList()) { origin ->
                    Camera3.markedAsHijacked(origin)
                    if (origin == targetSurface) {
                        modified = true
                        originSurface = WeakReference(origin)
                        NB.updateCameraExtendedData(origin)
                        return@mapTo BlackHole.surface
                    }
                    return@mapTo origin
                }
                if (modified) field.set(outputConfiguration, modifiedSurfaces)
            }
            handleStateCallback(sessionConfiguration.stateCallback)
            chain.proceed()
        }
    }
    private fun Class<*>.createCaptureSessionWithSurfacesHook() {
        val createCaptureSession = getDeclaredMethod(
            "createCaptureSession",
            List::class.java,
            CameraCaptureSession.StateCallback::class.java,
            Handler::class.java)
        magic.hook(createCaptureSession).intercept { chain ->
            if (!SM.readyForHook) return@intercept chain.proceed()
            val camera = chain.thisObject as CameraDevice
            camera.updateBaseData()
            @Suppress("UNCHECKED_CAST")
            val surfaces = chain.args[0] as List<Surface>
            val newList = surfaces.mapTo(ArrayList()) { origin ->
                Camera3.markedAsHijacked(origin)
                val (width, height, format) = NB.getSurfaceInfo(origin)
                Dog.i(TAG, "[List<Surface>] ${origin.shortId}, size: ${width}x${height}, format: $format", SM.enableLog)
                if (format == 1) {
                    originSurface = WeakReference(origin)
                    NB.updateCameraExtendedData(origin)
                    return@mapTo BlackHole.surface
                }
                return@mapTo origin
            }

            val stateCallback = chain.args[1] as CameraCaptureSession.StateCallback
            handleStateCallback(stateCallback)

            val newArgs = chain.args.toTypedArray()
            newArgs[0] = newList
            chain.proceed(newArgs)
        }
    }
    private fun Class<*>.closeHook() {
        val close = getDeclaredMethod("close")
        magic.hook(close).intercept { chain ->
            val camera = chain.thisObject as CameraDevice
            if (camera.isActiveRef) {
                Camera3.stop()
                Dog.w(TAG, "stop renderer for: $camera.", SM.enableLog)
                originSurface.clear()
                Camera3.clearHijackedList()
            }
            chain.proceed()
        }
    }
    private fun Class<*>.addTargetHook() {
        val addTarget = getDeclaredMethod("addTarget", Surface::class.java)
        magic.hook(addTarget).intercept { chain ->
            if (!SM.readyForHook) return@intercept chain.proceed()
            val origin = chain.args[0] as Surface
            Dog.i(TAG, "app wanna addTarget ${origin.shortId}", SM.enableLog)
            val cachedSurface = originSurface.get()
            if (origin != cachedSurface) return@intercept chain.proceed()
            val newArgs = chain.args.toTypedArray()
            newArgs[0] = BlackHole.surface
            return@intercept chain.proceed(newArgs)
        }
    }

    private fun Class<*>.removeTargetHook() {
        val removeTarget = getDeclaredMethod("removeTarget", Surface::class.java)
        magic.hook(removeTarget).intercept { chain ->
            if (!SM.readyForHook) return@intercept chain.proceed()
            val origin = chain.args[0] as Surface
            Dog.i(TAG, "app wanna removeTarget ${origin.shortId}", SM.enableLog)
            val cachedSurface = originSurface.get()
            if (origin != cachedSurface) return@intercept chain.proceed()
            val newArgs = chain.args.toTypedArray()
            newArgs[0] = BlackHole.surface
            return@intercept chain.proceed(newArgs)
        }
    }
}



