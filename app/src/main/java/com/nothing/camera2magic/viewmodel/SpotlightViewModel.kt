package com.nothing.camera2magic.viewmodel

import android.app.Application
import android.content.ContentUris
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.lang.Exception

class SpotlightViewModel(
    private val app: Application,
    private val repository: ConfigRepository) : ViewModel() {

    private val _videoThumbnail = MutableStateFlow<Bitmap?>(null)
    val videoThumbnail = _videoThumbnail.asStateFlow()

    private val _imageThumbnail = MutableStateFlow<Bitmap?>(null)
    val imageThumbnail = _imageThumbnail.asStateFlow()

    private val _uiState = MutableStateFlow(SpotlightUiState())
    val uiState = _uiState.asStateFlow()

    init {
        // check if video file deleted
        performHealthCheckAndRefresh()
         loadInitialSettings()
    }

    fun performHealthCheckAndRefresh() {
        loadAndVerifyMedia(MediaType.VIDEO)
        loadAndVerifyMedia(MediaType.IMAGE)
    }

    fun onModuleToggled() {
        _uiState.update { currentState ->
            val newState = !currentState.moduleEnabled
            repository.moduleEnabled = newState
            currentState.copy(moduleEnabled = newState)
        }
    }

    fun onVideoSelected(uri: Uri?) {
        handleMediaSelection(uri, MediaType.VIDEO)
    }

    fun onImageSelected(uri: Uri?) {
        handleMediaSelection(uri, MediaType.IMAGE)
    }

    fun clearVideo() {
        _videoThumbnail.value = null
        repository.videoId = -1L
    }

    fun clearImage() {
        _imageThumbnail.value = null
        repository.imageId = -1L
    }

    private fun loadInitialSettings() {
        _uiState.update {
            it.copy(
                moduleEnabled = repository.moduleEnabled
            )
        }
    }

    private fun handleMediaSelection(uri: Uri?, mediaType: MediaType) {

        if (uri == null) return
        val mediaId = try {
            uri.lastPathSegment?.toLongOrNull()
        } catch (_: kotlin.Exception) { null }
        if (mediaId != null) {
            when(mediaType) {
                MediaType.VIDEO -> repository.videoId = mediaId
                MediaType.IMAGE -> repository.imageId = mediaId
            }
            loadAndVerifyMedia(mediaType, mediaId)
        }
    }

    private fun updateThumbnailState(mediaType: MediaType, thumbnail: Bitmap?) {
        when (mediaType) {
            MediaType.VIDEO -> _videoThumbnail.value = thumbnail
            MediaType.IMAGE -> _imageThumbnail.value = thumbnail
        }
    }

    private fun loadAndVerifyMedia(mediaType: MediaType, mediaIdOverride: Long? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val mediaId = mediaIdOverride ?: if (mediaType == MediaType.VIDEO) {
                repository.videoId
            } else {
                repository.imageId
            }
            if (mediaId == -1L) {
                updateThumbnailState(mediaType, null)
                return@launch
            }

            var thumbnail: Bitmap? = null
            var isMediaValid = false
            try {
                val contentUri = when (mediaType) {
                    MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    MediaType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val uri = ContentUris.withAppendedId(contentUri, mediaId)
                app.contentResolver.openFileDescriptor(uri, "r")?.use {
                    isMediaValid = true
                    thumbnail = app.contentResolver.loadThumbnail(uri, Size(720, 1280), null)
                }
            } catch (e: Exception) {
                isMediaValid = false
            }

            if (isMediaValid) {
                updateThumbnailState(mediaType, thumbnail)
            } else {
                updateThumbnailState(mediaType, null)
                if (mediaIdOverride == null) {
                    when (mediaType) {
                        MediaType.VIDEO -> repository.videoId = -1L
                        MediaType.IMAGE -> repository.imageId = -1L
                    }
                }
            }
        }
    }

    private enum class MediaType {
        VIDEO, IMAGE
    }
}

data class SpotlightUiState(
    val moduleEnabled: Boolean = true,
    val mediaType: Int = 0
)