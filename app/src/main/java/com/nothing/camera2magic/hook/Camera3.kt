package com.nothing.camera2magic.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture

import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.view.Surface
import androidx.annotation.OptIn

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.MagicHook
import com.nothing.camera2magic.utils.Dog

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import com.nothing.camera2magic.hook.NativeBridge as NB
import com.nothing.camera2magic.hook.SourceManager as SM

class Camera3 {

    companion object {
        private const val TAG = "[Camera3]"

        private val camera3Handler = Camera3Extended.handler

        private val context: Context get() = GlobalState.appContext

        @Volatile
        private var initialized = AtomicBoolean(false)
        private var player: ExoPlayer? = null
        private var pfd: ParcelFileDescriptor? = null
        private var imageRendering: Boolean = false
        private var cachedBitmap: Bitmap? = null
        private var oesTextureId: Int = 0
        private var surface: Surface? = null
        private var surfaceTexture: SurfaceTexture? = null
    }

    enum class State { IDLE, BUFFERING, READY, ENDED, PLAYING, PAUSE, ERROR }
    var onPlayerStateChangeListener: ((state: State) -> Unit)? = null

    private val playerListener = object : Player.Listener {

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val pixelRatio = videoSize.pixelWidthHeightRatio
            val width = (videoSize.width * pixelRatio).toInt()
            val height = videoSize.height
            val rotation = videoSize.unappliedRotationDegrees
            NB.updateFrameInfo(width, height, rotation)
            surfaceTexture?.setDefaultBufferSize(width, height)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when(playbackState) {
                Player.STATE_IDLE -> notifyState(State.IDLE)
                Player.STATE_BUFFERING -> notifyState(State.BUFFERING)
                Player.STATE_READY -> notifyState(State.READY)
                Player.STATE_ENDED -> notifyState(State.ENDED)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) notifyState(State.PLAYING)
            else if (player?.playbackState != Player.STATE_ENDED) notifyState(State.PAUSE)
        }

        override fun onPlayerError(error: PlaybackException) {
            Dog.e(TAG, "${error.errorCodeName} - ${error.message}", error, true)
            notifyState(State.ERROR)
        }
    }

    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        oesTextureId = NB.createOESTexture()
        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(16, 16)
            setOnFrameAvailableListener({ _ ->
                NB.notifyFrameAvailable()
            }, camera3Handler)
        }

        NB.setSurfaceTexture(surfaceTexture!!)
        surface = Surface(surfaceTexture)

        player = ExoPlayer.Builder(GlobalState.appContext).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            addListener(playerListener)
        }
        Dog.i(TAG, "camera3 client initialized.", SM.enableLog)
    }

    fun start(magic: MagicHook, validMedia: ValidMedia) {
        camera3Handler.post {
            init()
            val (name, type) = validMedia
            when (type) {
                MagicType.NETWORK_RTSP -> {}
                MagicType.LOCAL_VIDEO  -> {
                    pfd = magic.openRemoteFile(name)
                    pfd?.let { handleLocalVideo(it) }
                }

                MagicType.LOCAL_IMAGE -> {
                    pfd = magic.openRemoteFile(name)
                    pfd?.let { handleLocalImage(it) }
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun handleLocalVideo(pfd: ParcelFileDescriptor) {
        val volumeValue = if (SM.playSound) 1f else 0f
        val factory = DataSource.Factory { MagicDataSource(pfd) }
        val mediaSourceFactory = DefaultMediaSourceFactory(factory)
        val mediaItem = MediaItem.fromUri("LOCAL://VIDEO")
        camera3Handler.post {
            player?.apply {
                volume = volumeValue
                setVideoSurface(surface)
                setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
                prepare()
                playWhenReady = true
            }
        }
    }

    private fun handleLocalImage(pfd: ParcelFileDescriptor) {

        runCatching {
            val fd = pfd.fileDescriptor

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            BitmapFactory.decodeFileDescriptor(fd, null, options)

            try {
                Os.lseek(fd, 0, OsConstants.SEEK_SET)
            } catch (e: Exception) {
                Dog.e(TAG, "Failed to seek file descriptor", e, SM.enableLog)
            }

            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            options.inSampleSize = calculateInSampleSize(options)


            val bitmap = BitmapFactory.decodeFileDescriptor(fd, null, options)
                ?: throw IllegalStateException("decode image failed.")

            NB.updateFrameInfo(bitmap.width, bitmap.height, 0)
            surfaceTexture?.setDefaultBufferSize(bitmap.width, bitmap.height)
            cachedBitmap = bitmap
            imageRendering = true
            camera3Handler.post(imageRenderRunnable)
        }.onFailure { e ->
            Dog.e(TAG, "${e.message}", e, true)
        }
    }

    private val imageRenderRunnable = object : Runnable {
        override fun run() {
            if (!initialized.get() || !imageRendering) return
            drawBitmapToSurface()
            if (imageRendering) camera3Handler.postDelayed(this, 33L)
        }
    }

    private fun drawBitmapToSurface() {
        val bitmap = cachedBitmap ?: return
        runCatching {
            val canvas = surface?.lockHardwareCanvas()// minSDK 26
            canvas?.let {
                it.drawColor(Color.BLACK, PorterDuff.Mode.CLEAR)
                it.drawBitmap(bitmap, 0f, 0f, null)
            }
            surface?.unlockCanvasAndPost(canvas)
        }
    }
    fun pause () {
        camera3Handler.post { player?.playWhenReady = false }
    }
    fun seekTo(position: Long) { // Ms
        camera3Handler.post { player?.seekTo(position) }
    }
    fun stop() {
        if (!initialized.get()) return
        camera3Handler.post {
            imageRendering = false
            camera3Handler.removeCallbacks(imageRenderRunnable)
            player?.release()
            releaseResources()
            initialized.set(false)
        }
    }

    fun releaseResources() {
        if (cachedBitmap != null) {
            val tmp = cachedBitmap
            cachedBitmap = null
            tmp?.recycle()
        }
        surface?.release()
        surface = null
        surfaceTexture?.release()
        surfaceTexture = null
        oesTextureId = 0
        pfd?.close()
        pfd = null
    }

    private fun notifyState(state: State) {
        onPlayerStateChangeListener?.invoke(state)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int = 1080, reqHeight: Int = 1920): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}