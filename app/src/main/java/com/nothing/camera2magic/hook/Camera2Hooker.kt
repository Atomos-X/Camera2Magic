package com.nothing.camera2magic.hook

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.view.Surface
import android.view.WindowManager
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.MagicEntry
import com.nothing.camera2magic.hook.NativeBridge.needStartRenderer

import com.nothing.camera2magic.hook.NativeBridge.needStopRenderer
import com.nothing.camera2magic.hook.NativeBridge.registerSurfaceIfNew
import com.nothing.camera2magic.hook.NativeBridge.releaseLastRegisteredSurface
import com.nothing.camera2magic.utils.Dog

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.BeforeHookCallback
import io.github.libxposed.api.XposedInterface.AfterHookCallback
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.ref.WeakReference
import java.util.WeakHashMap

object Camera2Hooker {
    private const val TAG = "[CAM2]"

    private val CameraDevice?.shortId : String
        get() = if (this == null) "null" else "@0x${Integer.toHexString(System.identityHashCode(this))}"

    private var activeCameraRef: WeakReference<Any>? = null
    private var cameraState = WeakHashMap<CameraDevice, CameraState>()
    private var blackHole: Surface? = null

    private fun destroyBlackHole() {
        blackHole?.release()
        blackHole = null
    }
    private fun getCameraState(camera: CameraDevice): CameraState {
        return synchronized(cameraState) {
            cameraState.getOrPut(camera) { CameraState() }
        }
    }
    private fun CameraState.saveCameraInfo(camera: CameraDevice) {
        val cameraIdStr = camera.id
        val context = GlobalState.appContext
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = cm.getCameraCharacteristics(cameraIdStr)

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val rotation = wm.defaultDisplay.rotation

        this.apiLevel = 2
        this.sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        this.facingFront = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        this.displayOrientation = rotation * 90
        this.packageName = GlobalState.packageName
    }
    private fun CameraState.bindSurface(surface: Surface) {
        val (width, height, _) = NativeBridge.getSurfaceInfo(surface)
        this.pictureWidth = width
        this.pictureHeight = height
        this.previewWidth = width
        this.previewHeight = height
        this.surface = surface
    }
    private fun handleStateCallback(callback: CameraCaptureSession.StateCallback) {
        val clazz = callback.javaClass
        val onConfigured = clazz.getDeclaredMethod("onConfigured",
            CameraCaptureSession::class.java)
        val onConfigureFailed = clazz.getDeclaredMethod("onConfigureFailed",
            CameraCaptureSession::class.java)
        magic.hook(onConfigured, OnConfiguredHooker::class.java)
        magic.hook(onConfigureFailed, OnConfigureFailedHooker::class.java)
    }

    private lateinit var magic: MagicEntry
    @SuppressLint("PrivateApi")
    fun initHooks(module: MagicEntry, param: PackageLoadedParam) {
        magic = module
        val classLoader = param.classLoader
        val deviceImpl = classLoader.loadClass("android.hardware.camera2.impl.CameraDeviceImpl")

        val createCaptureSessionNew = deviceImpl.getDeclaredMethod(
            "createCaptureSession",
            SessionConfiguration::class.java)

        magic.hook(createCaptureSessionNew, CreateCaptureSessionNewHooker::class.java)

        val createCaptureSessionOld = deviceImpl.getDeclaredMethod(
            "createCaptureSession",
            List::class.java,
            CameraCaptureSession.StateCallback::class.java,
            Handler::class.java)
        magic.hook(createCaptureSessionOld, CreateCaptureSessionOldHooker::class.java)

        val closeMethod = deviceImpl.getDeclaredMethod("close")
        magic.hook(closeMethod, CloseHooker::class.java)
    }

    class CreateCaptureSessionNewHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                if (!SourceManager.isReadyForHook()) return
                val camera = callback.thisObject as CameraDevice
                activeCameraRef = WeakReference(camera)

                val state = getCameraState(camera)
                state.saveCameraInfo(camera)
                /*
                // 抢先成为surface的生产者，对于大部分社交类应用（单流）足够了
                val surfaces = getSurfaceListFrom(callback.args[0])
                val targetSurface = getTargetFrom(surfaces) ?: return
                state.bindSurface(targetSurface)
                registerSurfaceIfNew(state, true)
                needStartRenderer()
                // 相机服务只会报错，这里不要阻断；
                */

                // 黑洞可能持有surface，先释放资源
                destroyBlackHole()

                val sessionConfiguration = callback.args[0] as SessionConfiguration

                @SuppressLint("SoonBlockedPrivateApi")
                val field = OutputConfiguration::class.java.getDeclaredField("mSurfaces")
                field.isAccessible = true

                sessionConfiguration.outputConfigurations.forEach { outputConfiguration ->
                    var modified = false
                    val surfaces = outputConfiguration.surfaces
                    val modifiedSurfaces = surfaces.mapTo(ArrayList<Surface>()) { origin ->
                        val (width, height, format) = NativeBridge.getSurfaceInfo(origin)
                        if (format == 1 && blackHole == null) {
                            state.bindSurface(origin)
                            @SuppressLint("Recycle")
                            val surfaceTexture = SurfaceTexture(false)
                                .apply { setDefaultBufferSize(1, 1) }
                            modified = true
                            return@mapTo Surface(surfaceTexture).also { blackHole = it }
                        }
                        origin
                    }
                    if (modified) field.set(outputConfiguration, modifiedSurfaces)
                }

                handleStateCallback(sessionConfiguration.stateCallback)
            }
        }
    }

    class CreateCaptureSessionOldHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                val camera = callback.thisObject as CameraDevice
                val state = getCameraState(camera)

                @Suppress("UNCHECKED_CAST")
                val surfaces = callback.args[0] as List<Surface>

                val newList = surfaces.mapTo(ArrayList<Surface>()) { origin ->
                    val (_, _, format) = NativeBridge.getSurfaceInfo(origin)
                    if (format == 1 && blackHole == null) {
                        state.bindSurface(origin)
                        @SuppressLint("Recycle")
                        val st = SurfaceTexture(false)
                            .apply { setDefaultBufferSize(1, 1) }
                        return@mapTo Surface(st).also { blackHole = it }
                    }
                    origin
                }
                callback.args[0] = newList
                val stateCallback = callback.args[1] as CameraCaptureSession.StateCallback
                handleStateCallback(stateCallback)
            }
        }
    }

    class OnConfiguredHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                val session = callback.args[0] as CameraCaptureSession
                val camera = session.device
                val state = getCameraState(camera)
                registerSurfaceIfNew(state, true)
                needStartRenderer()
            }
        }
    }

    class OnConfigureFailedHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                Dog.e(TAG, "createCaptureSession failed.", null, true)
                destroyBlackHole()
                activeCameraRef = null
            }
        }
    }

    class CloseHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun after(callback: AfterHookCallback) {
                val activeCamera = activeCameraRef?.get()
                val closingCamera = callback.thisObject as CameraDevice
                if (activeCamera != null && closingCamera === activeCamera) {
                    needStopRenderer()
                    releaseLastRegisteredSurface()
                    destroyBlackHole()
                    activeCameraRef = null
                }
            }
        }
    }
}