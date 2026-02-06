package com.nothing.camera2magic.hook

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
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
    private var activeCameraRef: WeakReference<Any>? = null
    private var cameraState = WeakHashMap<CameraDevice, CameraState>()

    private fun getCameraState(camera: CameraDevice): CameraState {
        return synchronized(cameraState) {
            cameraState.getOrPut(camera) { CameraState() }
        }
    }

    private fun getSurfaceListFrom(obj: Any?): List<Surface> {
        return when (obj) {
            is SessionConfiguration -> {
                obj.outputConfigurations.mapNotNull { it.surface }
            }
            is List<*> -> {
                val surfaces = obj.filterIsInstance<Surface>()
                if (surfaces.isNotEmpty()) return surfaces
                obj.filterIsInstance<OutputConfiguration>().mapNotNull { it.surface }
            }
            else -> emptyList()
        }
    }

    private fun getTargetFrom(surfaces: List<Surface>): Surface? {

        var targetSurface: Surface? = null

        val validSurfaces = surfaces.filter { it.isValid }

        // 步骤 A: 找 Format 34 (IMPLEMENTATION_DEFINED)
        val previewCandidates = validSurfaces.filter {
            val fmt = NativeBridge.getSurfaceInfo(it)[2]
            fmt == 34 // 0x22
        }

        if (previewCandidates.isNotEmpty()) {
            // 选面积最大的
            targetSurface = previewCandidates.maxByOrNull {
                val info = NativeBridge.getSurfaceInfo(it)
                info[0] * info[1]
            }
        }

        // 步骤 B: 没找到 34？那就降级找 Format 1
        if (targetSurface == null) {
            Dog.w(TAG, "No Format-34 surface found! Fallback to Format-1", SourceManager.enableLog)
            targetSurface = validSurfaces.firstOrNull {
                val fmt = NativeBridge.getSurfaceInfo(it)[2]
                fmt == 1
            }
        }
        return targetSurface
    }

    @SuppressLint("PrivateApi")
    fun initHooks(module: MagicEntry, param: PackageLoadedParam) {
        val classLoader = param.classLoader
        val cameraDeviceImplClass = classLoader.loadClass("android.hardware.camera2.impl.CameraDeviceImpl")
        val constructors = cameraDeviceImplClass.declaredConstructors
        constructors.forEach { method ->
            module.hook(method, CameraOpen::class.java)
        }

        val closeMethod = cameraDeviceImplClass.getDeclaredMethod("close")
        module.hook(closeMethod, CameraClose::class.java)

        val createCaptureSessionMethods = cameraDeviceImplClass.declaredMethods.filter {
            it.name == "createCaptureSession"
        }
        createCaptureSessionMethods.forEach { method ->
            module.hook(method, CameraCreateCaptureSession::class.java)
        }
    }

    class CameraOpen : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun after(callback: AfterHookCallback) {
                val context = GlobalState.appContext
                val camera = callback.thisObject as CameraDevice
                activeCameraRef = WeakReference(camera)

                val cameraIdStr = callback.args[0] as String
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val characteristics = manager.getCameraCharacteristics(cameraIdStr)

                val state = getCameraState(camera)
                state.apiLevel = 2
                state.sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                state.facingFront = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                state.packageName = GlobalState.packageName
            }
        }
    }
    class CameraClose : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun after(callback: AfterHookCallback) {
                val closingCamera = callback.thisObject as CameraDevice
                val activeCamera = activeCameraRef?.get()
                if (activeCamera != null && closingCamera === activeCamera) {
                    needStopRenderer()
                    releaseLastRegisteredSurface()
                    activeCameraRef = null
                }
            }
        }
    }
    class CameraCreateCaptureSession : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                if (!SourceManager.isReadyForHook()) return

                val context = GlobalState.appContext
                val camera = callback.thisObject as CameraDevice
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

                @Suppress("DEPRECATION")
                val rotation = windowManager.defaultDisplay.rotation

                val state = getCameraState(camera)
                state.displayOrientation = rotation * 90

                val surfaces = getSurfaceListFrom(callback.args[0])
                val targetSurface = getTargetFrom(surfaces)

                targetSurface?.let { surface ->
                    val info = NativeBridge.getSurfaceInfo(surface)
                    state.pictureWidth = info[0]
                    state.pictureHeight = info[1]
                    state.surface = surface
                }
                registerSurfaceIfNew(state, true)
                needStartRenderer()
            }
        }
    }
}