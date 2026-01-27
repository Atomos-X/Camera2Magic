package com.nothing.camera2magic.viewmodel

import android.app.Application
import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.lang.Exception

enum class MediaType(val value: Int, val mimeType: String) {
    VIDEO(0, "video/*"),
    IMAGE(1, "image/*"),
    UNKNOWN(-1, "unknown");
    companion object {
        fun fromValue(value: Int): MediaType {
            return entries.find { it.value == value } ?: UNKNOWN
        }
    }
}

data class SpotlightUiState(
    val moduleEnabled: Boolean = true,
    val currentType: MediaType = MediaType.VIDEO
)

class SpotlightViewModel(
    private val app: Application,
    private val repository: ConfigRepository
) : ViewModel() {

    private val _thumbnails = MutableStateFlow<Map<MediaType, Bitmap?>>(emptyMap())
    val thumbnails = _thumbnails.asStateFlow()

    private val _uiState = MutableStateFlow(SpotlightUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadInitialSettings()
        performHealthCheckAndRefresh()
    }

    fun onModuleToggled() {
        _uiState.update { currentState ->
            val newState = !currentState.moduleEnabled
            repository.moduleEnabled = newState
            currentState.copy(moduleEnabled = newState)
        }
    }
    fun onMediaSelected(type: MediaType, uri: Uri?) {
        if (uri == null) return
        val mediaId = try {
            uri.lastPathSegment?.toLongOrNull()
        } catch (_: kotlin.Exception) { null }
        if (mediaId != null) {
            saveMediaId(type, mediaId)
            loadAndVerifyMedia(type, mediaId)
        }
    }
    fun clearMedia(type: MediaType) {
        when (type) {
            MediaType.VIDEO -> repository.videoId = -1L
            MediaType.IMAGE -> repository.imageId = -1L
            MediaType.UNKNOWN -> {}
        }
        updateThumbnailState(type, null)
    }
    fun performHealthCheckAndRefresh() {
        MediaType.entries.filter { it != MediaType.UNKNOWN }.forEach { type ->
            loadAndVerifyMedia(type)
        }
    }

    private fun saveMediaId(type: MediaType, id: Long) {
        when (type) {
            MediaType.VIDEO -> repository.videoId = id
            MediaType.IMAGE -> repository.imageId = id
            MediaType.UNKNOWN -> {}
        }
    }
    private fun loadInitialSettings() {
        _uiState.update {
            it.copy(
                moduleEnabled = repository.moduleEnabled,
                currentType = MediaType.fromValue(repository.mediaType)
            )
        }
    }
    private fun getMediaId(type: MediaType): Long {
        return when (type) {
            MediaType.VIDEO -> repository.videoId
            MediaType.IMAGE -> repository.imageId
            MediaType.UNKNOWN -> -1L
        }
    }
    private fun loadAndVerifyMedia(type: MediaType, mediaIdOverride: Long? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val mediaId = mediaIdOverride ?: getMediaId(type)
            if (mediaId == -1L) {
                updateThumbnailState(type, null)
                return@launch
            }

            var thumbnail: Bitmap? = null
            var isMediaValid = false

            try {
                val contentUri = when (type) {
                    MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    MediaType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    else -> return@launch
                }

                val uri = ContentUris.withAppendedId(contentUri, mediaId)
                app.contentResolver.openFileDescriptor(uri, "r")?.use {
                    isMediaValid = true
                    thumbnail = app.contentResolver.loadThumbnail(uri, Size(720, 1280), null)
                }
            } catch (_: Exception) {
                isMediaValid = false
            }
            if (isMediaValid) {
                updateThumbnailState(type, thumbnail)
            } else {
                updateThumbnailState(type, null)
                if (mediaIdOverride == null) {
                    saveMediaId(type, -1L)
                }
            }
        }
    }
    private fun updateThumbnailState(mediaType: MediaType, thumbnail: Bitmap?) {
        if (mediaType == MediaType.UNKNOWN) return
        _thumbnails.update { currentMap ->
            currentMap + (mediaType to thumbnail)
        }
    }
}