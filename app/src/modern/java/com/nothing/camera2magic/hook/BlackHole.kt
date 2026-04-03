package com.nothing.camera2magic.hook
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

object BlackHole {
    private const val TAG = "[BlackHole]"

    @Volatile
    lateinit var surface: Surface
        private set
    @Volatile
    lateinit var surfaceTexture: SurfaceTexture
        private set

    @Volatile
    private var texName = 0
    @Volatile
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    @Volatile
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private val camera3ExtendedThread = HandlerThread("camera3Extended").apply { start() }
    private val camera3ExtendedHandle = Handler(camera3ExtendedThread.looper)

    init {
        initBlackHole()
    }

    private fun initEGL(): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)

        if (!EGL14.eglChooseConfig(
                eglDisplay, configAttribs,
                0, configs, 0, 1, numConfigs, 0)) {

            return false
        }

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )

        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0],
            EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

        if (eglContext == null) return false

        val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        val pbuffer = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], pbufferAttribs, 0)

        return EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
    }
    private fun initBlackHole() {
        if (!initEGL()) return

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        texName = texIds[0]
        surfaceTexture = SurfaceTexture(texName).apply {
            setDefaultBufferSize(320, 240)
            setOnFrameAvailableListener({ texture ->
                camera3ExtendedHandle.post { runCatching { texture.updateTexImage() } }
            }, camera3ExtendedHandle)
        }
        surface = Surface(surfaceTexture)
    }
}