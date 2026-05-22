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
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.MagicHook
import com.nothing.camera2magic.hook.NativeBridge.needStartRenderer
import com.nothing.camera2magic.hook.NativeBridge.needStopRenderer
import com.nothing.camera2magic.hook.NativeBridge.registerSurfaceIfNew
import com.nothing.camera2magic.hook.NativeBridge.releaseLastRegisteredSurface

import com.nothing.camera2magic.utils.Dog
import com.nothing.camera2magic.utils.shortId

import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

object Camera2Hooker {
    private const val TAG = "[CAM2]"
    private const val MAX_PREVIEW_AREA = 3840 * 2160

    private val CameraDevice?.shortId : String
        get() = if (this == null) "null" else "@0x${Integer.toHexString(System.identityHashCode(this))}"
    private lateinit var magic: MagicHook
    private val hookedClasses = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>()))
    private var activeCameraRef: WeakReference<Any>? = null
    private var cameraState = WeakHashMap<CameraDevice, CameraState>()
    private data class SurfaceInfo(
        val surface: Surface,
        val width: Int,
        val height: Int,
        val format: Int
    )

    private fun getCameraState(camera: CameraDevice): CameraState {
        return synchronized(cameraState) {
            cameraState.getOrPut(camera) { CameraState() }
        }
    }

    private fun readSurfaceInfo(surface: Surface): SurfaceInfo? {
        return runCatching {
            val (width, height, format) = NativeBridge.getSurfaceInfo(surface)
            SurfaceInfo(surface, width, height, format)
        }.getOrNull()
    }

    private fun SurfaceInfo.isPreviewCandidate(): Boolean {
        val area = width.toLong() * height.toLong()
        return format == 1 && area in 1..MAX_PREVIEW_AREA
    }

    private fun selectPreviewSurface(surfaces: List<Surface>): SurfaceInfo? {
        val infos = surfaces.mapNotNull(::readSurfaceInfo)
        infos.forEach { info ->
            Dog.i(
                TAG,
                "surface[${info.surface.shortId}] ${info.width}x${info.height}, format=${info.format}",
                SourceManager.enableLog
            )
        }
        return infos
            .filter { it.isPreviewCandidate() }
            .minByOrNull { it.width.toLong() * it.height.toLong() }
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
    private fun CameraState.bindSurface(info: SurfaceInfo) {
        val surface = info.surface
        val width = info.width
        val height = info.height
        Dog.i(TAG, "bind preview surface[${surface.shortId}] ${width}x${height}", SourceManager.enableLog)
        this.pictureWidth = width
        this.pictureHeight = height
        this.previewWidth = width
        this.previewHeight = height
        this.surface = surface
    }
    private fun handleStateCallback(callback: CameraCaptureSession.StateCallback) {
        val clazz = callback.javaClass
        if (hookedClasses.add(clazz)) {
            val onConfigured = clazz.getDeclaredMethod("onConfigured",
                CameraCaptureSession::class.java)

            magic.hook(onConfigured).intercept { chain ->
                val session = chain.args[0] as CameraCaptureSession
                val camera = session.device
                val result = chain.proceed()
                val activeCamera = activeCameraRef?.get()
                if (activeCamera === camera && SourceManager.isReadyForHook()) {
                    val state = getCameraState(camera)
                    if (state.surface?.isValid == true) {
                        registerSurfaceIfNew(state, true)
                        needStartRenderer()
                    } else {
                        Dog.e(TAG, "Skip renderer start; preview surface is invalid.", null, SourceManager.enableLog)
                    }
                }
                result
            }

            val onConfigureFailed = clazz.getDeclaredMethod("onConfigureFailed",
                CameraCaptureSession::class.java)

            magic.hook(onConfigureFailed).intercept { chain ->
                Dog.e(TAG, "CameraCaptureSession.StateCallback: onConfigureFailed.", null, true)
                BlackHoleMapper.clearAll()
                activeCameraRef = null
                chain.proceed()
            }
        }
    }
    @SuppressLint("PrivateApi")
    fun initHooks(module: MagicHook, param: PackageReadyParam) {
        magic = module
        val classLoader = param.classLoader
        val deviceImplClass = classLoader.loadClass("android.hardware.camera2.impl.CameraDeviceImpl")
        deviceImplClass.apply {
            hookCreateCaptureSessionWithConfiguration()
            hookCreateCaptureSessionWithSurfaces()
            hookClose()
        }
        val builderClass = classLoader.loadClass("android.hardware.camera2.CaptureRequest\$Builder")
        builderClass.apply {
            hookAddTarget()
            hookRemoveTarget()
        }
    }

    private fun Class<*>.hookCreateCaptureSessionWithConfiguration() {
        val createCaptureSession = getDeclaredMethod(
            "createCaptureSession",
            SessionConfiguration::class.java)
        magic.hook(createCaptureSession).intercept { chain ->
            if (!SourceManager.isReadyForHook()) return@intercept chain.proceed()
            val camera = chain.thisObject as CameraDevice
            activeCameraRef = WeakReference(camera)

            val state = getCameraState(camera)
            state.saveCameraInfo(camera)

            val sessionConfiguration = chain.args[0] as SessionConfiguration
            val previewSurface = selectPreviewSurface(
                sessionConfiguration.outputConfigurations.flatMap { it.surfaces }
            )

            @SuppressLint("SoonBlockedPrivateApi")
            val field = OutputConfiguration::class.java.getDeclaredField("mSurfaces")
            field.isAccessible = true
            sessionConfiguration.outputConfigurations.forEach { outputConfiguration ->
                var modified = false
                val surfaces = outputConfiguration.surfaces
                val modifiedSurfaces = surfaces.mapTo(ArrayList<Surface>()) { origin ->
                    if (origin === previewSurface?.surface) {
                        modified = true
                        state.bindSurface(previewSurface)
                        return@mapTo BlackHoleMapper.createBlackHole(
                            origin,
                            previewSurface.width,
                            previewSurface.height
                        )
                    }
                    origin
                }
                if (modified) field.set(outputConfiguration, modifiedSurfaces)
            }
            handleStateCallback(sessionConfiguration.stateCallback)
            chain.proceed()
        }
    }

    private fun Class<*>.hookCreateCaptureSessionWithSurfaces() {
        val createCaptureSession = getDeclaredMethod(
            "createCaptureSession",
            List::class.java,
            CameraCaptureSession.StateCallback::class.java,
            Handler::class.java)
        magic.hook(createCaptureSession).intercept { chain ->
            if (!SourceManager.isReadyForHook()) return@intercept chain.proceed()
            val camera = chain.thisObject as CameraDevice
            activeCameraRef = WeakReference(camera)
            val state = getCameraState(camera)
            state.saveCameraInfo(camera)
            @Suppress("UNCHECKED_CAST")
            val surfaces = chain.args[0] as List<Surface>
            val previewSurface = selectPreviewSurface(surfaces)
            val newList = surfaces.mapTo(ArrayList()) { origin ->
                if (origin === previewSurface?.surface) {
                    state.bindSurface(previewSurface)
                    return@mapTo BlackHoleMapper.createBlackHole(
                        origin,
                        previewSurface.width,
                        previewSurface.height
                    )
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

    private fun Class<*>.hookClose() {
        val close = getDeclaredMethod("close")
        magic.hook(close).intercept { chain ->
            val activeCamera = activeCameraRef?.get()
            val closingCamera = chain.thisObject as CameraDevice

            if (activeCamera != null && closingCamera === activeCamera) {
                Dog.i(TAG, "camera[${closingCamera.shortId}] close.", true)
                needStopRenderer()
                releaseLastRegisteredSurface()
                BlackHoleMapper.clearAll()
                activeCameraRef = null
            }
            chain.proceed()
        }
    }

    private fun Class<*>.hookAddTarget() {
        val addTarget = getDeclaredMethod("addTarget", Surface::class.java)
        magic.hook(addTarget).intercept { chain ->
            val origin = chain.args[0] as Surface
            if (!SourceManager.isReadyForHook()) {
                return@intercept chain.proceed()
            }
            val blackHole = BlackHoleMapper.getBlackHole(origin) ?: run {
                val info = readSurfaceInfo(origin)
                if (info?.isPreviewCandidate() == true) {
                    Dog.i(TAG, "early map request target[${origin.shortId}] ${info.width}x${info.height}", SourceManager.enableLog)
                    BlackHoleMapper.createBlackHole(origin, info.width, info.height)
                } else {
                    null
                }
            } ?: return@intercept chain.proceed()
            chain.proceed(arrayOf(blackHole))
        }
    }

    private fun Class<*>.hookRemoveTarget() {
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
