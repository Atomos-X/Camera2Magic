package com.nothing.camera2magic.viewmodel

import android.app.Application
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.lang.Exception

class SpotlightViewModel(
    private val app: Application,
    private val prefs: SharedPreferences?
) : AndroidViewModel(app) {

    private val _videoThumbnail = MutableStateFlow<Bitmap?>(null)
    val videoThumbnail = _videoThumbnail.asStateFlow()

    private val _imageThumbnail = MutableStateFlow<Bitmap?>(null)
    val imageThumbnail = _imageThumbnail.asStateFlow()

    private val _uiState = MutableStateFlow(SpotlightUiState())
    val uiState = _uiState.asStateFlow()

    companion object {
        const val KEY_VIDEO_ID = "video_id"
        const val KEY_IMAGE_ID = "image_id"
        const val KEY_MODULE_ENABLED = "module_enabled"
    }

    init {
        // check if video file deleted
        performHealthCheckAndRefresh()
        loadInitialSettings()
    }

    fun performHealthCheckAndRefresh() {
        loadAndVerifyMedia(KEY_VIDEO_ID, MediaType.VIDEO)
        loadAndVerifyMedia(KEY_IMAGE_ID, MediaType.IMAGE)
    }

    fun onVideoSelected(uri: Uri?) {
        handleMediaSelection(uri, MediaType.VIDEO)
    }

    fun onImageSelected(uri: Uri?) {
        handleMediaSelection(uri, MediaType.IMAGE)
    }

    fun clearVideo() {
        _videoThumbnail.value = null
        removeString(KEY_VIDEO_ID)
    }

    fun clearImage() {
        _imageThumbnail.value = null
        removeString(KEY_IMAGE_ID)
    }

    fun onModuleToggled() {
        val newState = !_uiState.value.isModuleEnabled
        _uiState.update { it.copy(isModuleEnabled = newState) }
        saveBoolean(KEY_MODULE_ENABLED, newState)
    }

    private fun loadInitialSettings() {
        _uiState.update {
            it.copy(
                isModuleEnabled = prefs?.getBoolean(KEY_MODULE_ENABLED, true) ?: true
            )
        }
    }

    private fun handleMediaSelection(uri: Uri?, mediaType: MediaType) {
        val key = if (mediaType == MediaType.VIDEO) KEY_VIDEO_ID else KEY_IMAGE_ID
        if (uri == null) {
            return
        }

        uri.lastPathSegment?.substringAfterLast(":")?.let { mediaId ->
            saveString(key, mediaId)
            loadAndVerifyMedia(key, mediaType, mediaId)
        }
    }

    private fun updateThumbnailState(mediaType: MediaType, thumbnail: Bitmap?) {
        when (mediaType) {
            MediaType.VIDEO -> _videoThumbnail.value = thumbnail
            MediaType.IMAGE -> _imageThumbnail.value = thumbnail
        }
    }

    private fun loadAndVerifyMedia(key: String, mediaType: MediaType, mediaIdOverride: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val mediaId = mediaIdOverride ?: prefs?.getString(key, null)
            if (mediaId == null) {
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
                val uri = Uri.withAppendedPath(contentUri, mediaId)
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
                    removeString(key)
                }
            }
        }
    }


    private fun saveString(key: String, value: String) {
        prefs?.edit()?.putString(key, value)?.apply()
    }

    private fun removeString(key: String) {
        prefs?.edit()?.remove(key)?.apply()
    }

    private fun saveBoolean(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(key, value)?.apply()
    }

    private enum class MediaType {
        VIDEO, IMAGE
    }
}

data class SpotlightUiState(
    val isModuleEnabled: Boolean = true
)
