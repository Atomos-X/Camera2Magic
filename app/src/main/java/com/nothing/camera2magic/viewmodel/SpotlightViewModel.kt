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

data class SpotlightUiState(
    val moduleEnabled: Boolean = true,
    val selectedMediaSource: MediaSource = MediaSource.LOCAL,
    val currentType: MediaType = MediaType.VIDEO,
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
    fun selectedMediaSourceFrom(value: Int) {
        val source = MediaSource.fromValue(value)
        _uiState.update { currentState ->
            repository.mediaSource = value
            currentState.copy(selectedMediaSource = source)
        }
    }

    fun setCurrentMediaType(type: MediaType) {
        _uiState.update { currentState ->
            repository.localMediaType = type.value
            currentState.copy(currentType = type)
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
    fun clearMediaBy(type: MediaType) {
        when (type) {
            MediaType.VIDEO -> repository.videoId = -1L
            MediaType.IMAGE -> repository.imageId = -1L
        }
        updateThumbnailState(type, null)
    }
    fun performHealthCheckAndRefresh() {
        MediaType.entries.forEach { type ->
            loadAndVerifyMedia(type)
        }
    }

    private fun saveMediaId(type: MediaType, id: Long) {
        when (type) {
            MediaType.VIDEO -> repository.videoId = id
            MediaType.IMAGE -> repository.imageId = id
        }
    }
    private fun loadInitialSettings() {
        _uiState.update {
            it.copy(
                moduleEnabled = repository.moduleEnabled,
                selectedMediaSource = MediaSource.fromValue(repository.mediaSource),
                currentType = MediaType.fromValue(repository.localMediaType)
            )
        }
    }
    private fun getMediaId(type: MediaType): Long {
        return when (type) {
            MediaType.VIDEO -> repository.videoId
            MediaType.IMAGE -> repository.imageId
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
    private fun updateThumbnailState(type: MediaType, thumbnail: Bitmap?) {
        _thumbnails.update { currentMap ->
            currentMap + (type to thumbnail)
        }
    }
}