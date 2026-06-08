package com.nothing.camera2magic.hook

import android.annotation.SuppressLint
import android.content.Context
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
import androidx.media3.common.util.UnstableApi
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.MagicHook
import com.nothing.camera2magic.hook.BlackHole.getBlackHole
import com.nothing.camera2magic.hook.BlackHole.gocBlackHole
import com.nothing.camera2magic.hook.SourceManager as SM
import com.nothing.camera2magic.hook.NativeBridge as NB
import com.nothing.camera2magic.utils.Dog
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executor

@SuppressLint("PrivateApi")
class Camera2Hooker(val magic: MagicHook, param: PackageReadyParam) : HookManager {

    companion object {
        private const val TAG = "[CAM2]"
        private const val CAMERA_DEVICE_IMPL = "android.hardware.camera2.impl.CameraDeviceImpl"
        private const val CAMERA_MANAGER = "android.hardware.camera2.CameraManager"
        private const val CAPTURE_REQUEST_BUILDER = $$"android.hardware.camera2.CaptureRequest$Builder"
        private var activatedCamera = WeakReference<Any>(null)
        private val camera3Map = WeakHashMap<Any, Camera3>()
        private val processName: String
            get() = GlobalState.processName
        private val CameraDevice.isActiveRef: Boolean
            get() = activatedCamera.get() == this
    }

    override val hookedClasses: MutableSet<Class<*>> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))


    init {
        val classLoader = param.classLoader

        classLoader.safeHook(CAMERA_MANAGER) {
            openCameraHook()
        }

        classLoader.safeHook(CAMERA_DEVICE_IMPL) {
            createCaptureSessionWithConfigurationHook()
            createCaptureSessionWithSurfacesHook()
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
        NB.updateCameraBaseData(2, facingFront, sensorOri, displayOri, processName)
        activatedCamera = WeakReference(this)
    }

    private fun Class<*>.onOpenedHook() {
        val onOpened = getDeclaredMethod("onOpened", CameraDevice::class.java)
        magic.hook(onOpened).intercept { chain ->
            val camera = chain.args[0] as CameraDevice
            camera.updateBaseData()
            Dog.w(TAG, "API[2] open camera: ${camera.shortId}", SM.enableLog)
            return@intercept chain.proceed()
        }
    }

    private fun Class<*>.onClosedHook() {
        val onClosed = getDeclaredMethod("onClosed", CameraDevice::class.java)
        magic.hook(onClosed).intercept { chain ->
            val camera = chain.args[0] as CameraDevice
            if (camera.isActiveRef) {
                camera3Map[camera]?.stop()
                BlackHole.clear()
                Dog.w(TAG, "API[2] close camera: ${camera.shortId}", SM.enableLog)
            }

            return@intercept chain.proceed()
        }
    }
    private fun Class<*>.openCameraHook() {
        val openCamera = getDeclaredMethod("openCamera",
            String::class.java, CameraDevice.StateCallback::class.java, Handler::class.java)
        magic.hook(openCamera).intercept { chain ->
            val stateCallBack = chain.args[1] as CameraDevice.StateCallback
            stateCallBack.javaClass.safeHook {
                onOpenedHook()
                onClosedHook()
            }
            return@intercept chain.proceed()
        }

        val openCameraWithExecutor = getDeclaredMethod("openCamera",
            String::class.java, Executor::class.java, CameraDevice.StateCallback::class.java)
        magic.hook(openCameraWithExecutor).intercept { chain ->
            val stateCallBack = chain.args[2] as CameraDevice.StateCallback
            stateCallBack.javaClass.safeHook {
                onOpenedHook()
                onClosedHook()
            }
            return@intercept chain.proceed()
        }
    }

    @OptIn(UnstableApi::class)
    private fun Class<*>.onConfiguredHook() {
        val onConfigured = getDeclaredMethod("onConfigured",
            CameraCaptureSession::class.java)

        magic.hook(onConfigured).intercept { chain ->
            val session = chain.args[0] as CameraCaptureSession
            val camera = session.device
            Dog.i(TAG, "[:onConfigured] ${BlackHole.originSurfaces.size} surface need to send.", SM.enableLog)
            BlackHole.originSurfaces.forEach { NB.addRenderTarget(it) }
            SM.validMedia?.let {
                val camera3 = Camera3()
                camera3Map[camera] = camera3
                camera3.start(magic, it)
            }
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
    private fun Class<*>.createCaptureSessionWithConfigurationHook() {
        val createCaptureSession = getDeclaredMethod(
            "createCaptureSession",
            SessionConfiguration::class.java)
        magic.hook(createCaptureSession).intercept { chain ->
            if (!SM.readyForHook) return@intercept chain.proceed()

            val sessionConfiguration = chain.args[0] as SessionConfiguration

            sessionConfiguration.stateCallback.javaClass.safeHook {
                onConfiguredHook()
                onConfigureFailedHook()
            }

            @SuppressLint("SoonBlockedPrivateApi")
            val field = OutputConfiguration::class.java.getDeclaredField("mSurfaces")
            field.isAccessible = true
            sessionConfiguration.outputConfigurations.forEach { outputConfiguration ->
                val surfaces = outputConfiguration.surfaces
                Dog.i(TAG, "[:createCaptureSession] outputConfiguration: $surfaces", SM.enableLog)

                val modifiedSurfaces = surfaces.map { origin ->
                    val (w, h ,f) = NB.getSurfaceInfo(origin)
                    if (f == 1 || f == 4) return@map origin.gocBlackHole
                    return@map origin
                }
                field.set(outputConfiguration, modifiedSurfaces)
            }
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

            val stateCallback = chain.args[1] as CameraCaptureSession.StateCallback
            stateCallback.javaClass.safeHook {
                onConfiguredHook()
                onConfigureFailedHook()
            }

            @Suppress("UNCHECKED_CAST")
            val surfaces = chain.args[0] as List<Surface>
            val count = surfaces.size
            Dog.i(TAG, "[:createCaptureSession] List<Surface>: $surfaces", SM.enableLog)
            val newList = surfaces.mapTo(ArrayList()) { origin ->
                val (w, h, f) = NB.getSurfaceInfo(origin)
                if (f == 1 || f == 4) return@mapTo origin.gocBlackHole
                return@mapTo origin
            }

            val newArgs = chain.args.toTypedArray()
            newArgs[0] = newList
            chain.proceed(newArgs)
        }
    }

    private fun Class<*>.addTargetHook() {
        val addTarget = getDeclaredMethod("addTarget", Surface::class.java)
        magic.hook(addTarget).intercept { chain ->
            if (!SM.readyForHook) return@intercept chain.proceed()
            val origin = chain.args[0] as Surface
            val (width, height, format) = NB.getSurfaceInfo(origin)
            Dog.i(TAG, "[:addTarget] ${origin.shortId}, format: $format, size: ${width}x${height}", SM.enableLog)

            if (format == 1 || format == 4) return@intercept chain.proceed(arrayOf(origin.gocBlackHole))
            if (format == 35) NB.updateAlgorithmSize(width, height)
            return@intercept chain.proceed()
        }
    }

    private fun Class<*>.removeTargetHook() {
        val removeTarget = getDeclaredMethod("removeTarget", Surface::class.java)
        magic.hook(removeTarget).intercept { chain ->
            if (!SM.readyForHook) return@intercept chain.proceed()
            val origin = chain.args[0] as Surface
            val (width, height, format) = NB.getSurfaceInfo(origin)
            Dog.i(TAG, "[:removeTarget] ${origin.shortId}, format: $format, size: ${width}x${height}", SM.enableLog)
            val oab = origin.getBlackHole ?: origin
            return@intercept chain.proceed(arrayOf(oab))
        }
    }
}



