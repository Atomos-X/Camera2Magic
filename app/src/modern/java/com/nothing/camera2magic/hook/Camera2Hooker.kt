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
import android.os.Looper
import android.view.Surface
import android.view.WindowManager
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.MagicHook
import com.nothing.camera2magic.hook.NativeBridge as NB
import com.nothing.camera2magic.utils.Dog
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

@SuppressLint("PrivateApi")
class Camera2Hooker(val magic: MagicHook, val param: PackageReadyParam) : HookManager {

    override val hookedClasses: MutableSet<Class<*>> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    companion object {
        private const val TAG = "[CAM2]"
        private val CameraDevice.state: CameraState
            get() = CameraRegistry.obtain(this) { apiLevel = 2 }
        private val CameraDevice.isActiveRef: Boolean
            get() = CameraRegistry.isActive(this)
        private const val CAMERA_DEVICE_IMPL = "android.hardware.camera2.impl.CameraDeviceImpl"
        private const val CAPTURE_REQUEST_BUILDER = "android.hardware.camera2.CaptureRequest\$Builder"
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

    private fun Class<*>.onConfiguredHook() {
        val onConfigured = getDeclaredMethod("onConfigured",
            CameraCaptureSession::class.java)

        magic.hook(onConfigured).intercept { chain ->
            val session = chain.args[0] as CameraCaptureSession
            val camera = session.device
            NB.registerSurfaceIfNew(camera.state, true)
            Handler(Looper.getMainLooper()).post { NB.needStartRenderer() }
            chain.proceed()
        }
    }
    private fun Class<*>.onConfigureFailedHook() {
        val onConfigureFailed = getDeclaredMethod("onConfigureFailed",
            CameraCaptureSession::class.java)

        magic.hook(onConfigureFailed).intercept { chain ->
            Dog.e(TAG, "CameraCaptureSession.StateCallback: onConfigureFailed.", null, true)
            BlackHoleMapper.clearAll()
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
            if (!SourceManager.isReadyForHook()) return@intercept chain.proceed()
            val camera = chain.thisObject as CameraDevice

            val cameraId = camera.id
            val context = GlobalState.appContext
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = cm.getCameraCharacteristics(cameraId)

            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            val rotation = wm.defaultDisplay.rotation

            camera.state.apply {
                this.apiLevel = 2
                this.sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                this.facingFront = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                this.displayOrientation = rotation * 90
                this.packageName = GlobalState.packageName
            }

            BlackHoleMapper.clearAll()

            val sessionConfiguration = chain.args[0] as SessionConfiguration

            @SuppressLint("SoonBlockedPrivateApi")
            val field = OutputConfiguration::class.java.getDeclaredField("mSurfaces")
            field.isAccessible = true
            sessionConfiguration.outputConfigurations.forEach { outputConfiguration ->
                var modified = false
                val surfaces = outputConfiguration.surfaces

                val modifiedSurfaces = surfaces.mapTo(ArrayList<Surface>()) { origin ->
                    val (width, height, format) = NB.getSurfaceInfo(origin)
                    Dog.i(TAG, "In outputConfiguration: ${origin.shortId}, format: $format", SourceManager.enableLog)
                    if (format == 1) {
                        modified = true
                        camera.state.apply {
                            this.pictureWidth = width
                            this.pictureHeight = height
                            this.previewWidth = width
                            this.previewHeight = height
                            this.surface = origin
                        }
                        return@mapTo BlackHoleMapper.createBlackHole(origin)
                    }
                    origin
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
            if (!SourceManager.isReadyForHook()) return@intercept chain.proceed()
            val camera = chain.thisObject as CameraDevice

            BlackHoleMapper.clearAll()
            @Suppress("UNCHECKED_CAST")
            val surfaces = chain.args[0] as List<Surface>
            val newList = surfaces.mapTo(ArrayList()) { origin ->
                val (width, height, format) = NB.getSurfaceInfo(origin)
                Dog.i(TAG, "In List<Surface> $origin, format: $format", SourceManager.enableLog)
                if (format == 1) {
                    camera.state.apply {
                        this.pictureWidth = width
                        this.pictureHeight = height
                        this.previewWidth = width
                        this.previewHeight = height
                        this.surface = origin
                    }
                    return@mapTo BlackHoleMapper.createBlackHole(origin)
                }
                origin
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
                Handler(Looper.getMainLooper()).post { NB.needStopRenderer() }
                NB.releaseLastRegisteredSurface()
                BlackHoleMapper.clearAll()
            }
            chain.proceed()
        }
    }
    private fun Class<*>.addTargetHook() {
        val addTarget = getDeclaredMethod("addTarget", Surface::class.java)
        magic.hook(addTarget).intercept { chain ->
            val origin = chain.args[0] as Surface
            Dog.i(TAG, "addTarget ${origin.shortId}", SourceManager.enableLog)
            val blackHole = BlackHoleMapper.getBlackHole(origin)
            if (!SourceManager.isReadyForHook() || blackHole == null) {
                return@intercept chain.proceed()
            }
            Dog.i(TAG, "Camera3 replace ${origin.shortId} with blackHole...", SourceManager.enableLog)
            chain.proceed(arrayOf(blackHole))
        }
    }
    private fun Class<*>.removeTargetHook() {
        val removeTarget = getDeclaredMethod("removeTarget", Surface::class.java)
        magic.hook(removeTarget).intercept { chain ->
            val origin = chain.args[0] as Surface
            val blackHole = BlackHoleMapper.getBlackHole(origin)
            if (!SourceManager.isReadyForHook() || blackHole == null) {
                return@intercept chain.proceed()
            }
            chain.proceed(arrayOf(blackHole))
        }
    }
}



