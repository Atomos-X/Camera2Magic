package com.nothing.camera2magic.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture

import android.net.Uri

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
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.utils.Dog

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import com.nothing.camera2magic.hook.NativeBridge as NB
import com.nothing.camera2magic.hook.SourceManager as SM
object Camera3 {
    private const val TAG = "[Camera3]"
    private val surfaceIsHijacked = Collections.newSetFromMap(ConcurrentHashMap<Surface, Boolean>())
    @Volatile
    private var initialized = AtomicBoolean(false)
    private var player: ExoPlayer? = null
    private var imageRendering: Boolean = false
    private var cachedBitmap: Bitmap? = null
    private var oesTextureId: Int = 0
    private var surface: Surface? = null
    private var surfaceTexture: SurfaceTexture? = null
    private lateinit var thread: HandlerThread
    private lateinit var camera3Handler: Handler
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
            Dog.e(TAG, "播放失败原因: ${error.errorCodeName} - ${error.message}", error, true)
            notifyState(State.ERROR)
        }
    }

    fun Context.initCamera3() {

        if (!initialized.compareAndSet(false, true)) return

        thread = HandlerThread("camera3").apply { start() }
        camera3Handler = Handler(thread.looper)

        camera3Handler.post {
            Dog.w(TAG, "init camera3..", SM.enableLog)

            oesTextureId = NB.createOESTexture()
            surfaceTexture = SurfaceTexture(oesTextureId).apply {
                setDefaultBufferSize(16, 16)
                setOnFrameAvailableListener({ _ ->
                    NB.notifyFrameAvailable()
                }, camera3Handler)
            }

            NB.setSurfaceTexture(surfaceTexture!!)
            player = ExoPlayer.Builder(this).build().apply {
                repeatMode = Player.REPEAT_MODE_ALL
                addListener(playerListener)
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun start(pfd: ParcelFileDescriptor) {
        if (!initialized.get()) return
        if (surface == null) surface = Surface(surfaceTexture)
        val volumeValue = if (SM.playSound) 1f else 0f

        val factory = DataSource.Factory { MagicDataSource(pfd) }
        val mediaSource = ProgressiveMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri("magic://video"))

        camera3Handler.post {
            player?.apply {
                volume = volumeValue
                setVideoSurface(surface)
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
            }
        }
    }

    fun start(media: ValidMedia) {
        if (!initialized.get()) return
        if (surface == null) surface = Surface(surfaceTexture)
        when(media.type) {
            MagicType.LOCAL_IMAGE -> handleImage(media.uri)
            else -> handleVideo(media.uri)
        }
    }

    private fun handleImage(uri: Uri) {
        val contentResolver = GlobalState.appContext.contentResolver
        runCatching {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, this)
                }
            }

            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            options.inSampleSize = calculateInSampleSize(options)

            val bitmap = contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: throw IllegalStateException("decode image failed.")

            NB.updateFrameInfo(bitmap.width, bitmap.height, 0)
            surfaceTexture?.setDefaultBufferSize(bitmap.width, bitmap.height)
            cachedBitmap = bitmap
            imageRendering = true
            camera3Handler.post(imageRenderRunnable)
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
    private fun handleVideo(uri: Uri) {
        val volumeValue = if (SM.playSound) 1f else 0f
        camera3Handler.post {
            player?.apply {
                volume = volumeValue
                setVideoSurface(surface)
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = true
            }
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
            player?.stop()
            player?.clearVideoSurface()
            releaseResources()
        }
    }
    fun releaseResources() {
        surfaceIsHijacked.clear()
        if (cachedBitmap != null) {
            val tmp = cachedBitmap
            cachedBitmap = null
            tmp?.recycle()
        }
        player?.clearMediaItems()
        player?.clearVideoSurface()
        surfaceTexture?.setDefaultBufferSize(16, 16)
        surface?.release()
        surface = null
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

    /**
     * 将所有原始surface记录
     */
    fun markedAsHijacked(origin: Surface) {
        surfaceIsHijacked.add(origin)
    }
    fun isHijacked(origin: Surface): Boolean {
        return surfaceIsHijacked.contains(origin)
    }
    fun clearHijackedList() {
        surfaceIsHijacked.clear()
    }
}