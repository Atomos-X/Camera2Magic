package com.nothing.camera2magic.viewmodel

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import android.webkit.MimeTypeMap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nothing.camera2magic.utils.Dog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.core.net.toUri
import kotlinx.coroutines.withContext

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

    companion object {
        private const val TAG = "[Spotlight VM]"
    }

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

    fun onMediaSelected(type: MediaType, uri: Uri) {
        try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            app.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: Exception) {
            Dog.e(TAG, "Failed to take persistable permission", e, repository.enableLog)
        }

        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    val mimeType = app.contentResolver.getType(uri)
                    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)

                    app.contentResolver.openInputStream(uri)?.use { inputStream ->
                        repository.prepareRemoteMedia(type.label, extension, inputStream)
                    } ?: false

                }.getOrDefault(false)
            }

            if (success) {
                loadAndVerifyMedia(type, uri)
            }
        }
    }
    fun clearMediaBy(type: MediaType) {
        when (type) {
            MediaType.VIDEO -> repository.videoUri = null
            MediaType.IMAGE -> repository.imageUri = null
        }
        updateThumbnailState(type, null)
    }
    fun performHealthCheckAndRefresh() {
        viewModelScope.launch {
            MediaType.entries.forEach { type ->
                loadAndVerifyMedia(type)
            }
        }
    }

    private fun saveMediaUri(type: MediaType, uri: String?) {
        when (type) {
            MediaType.VIDEO -> repository.videoUri = uri
            MediaType.IMAGE -> repository.imageUri = uri
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
    private fun getMediaUri(type: MediaType): Uri? {
        return when (type) {
            MediaType.VIDEO -> repository.videoUri?.toUri()
            MediaType.IMAGE -> repository.imageUri?.toUri()
        }
    }

    private suspend fun loadAndVerifyMedia(type: MediaType, uriOverride: Uri? = null) {
        withContext(Dispatchers.IO) {
            val targetUri = uriOverride ?: getMediaUri(type)
            if (targetUri == null) {
                updateThumbnailState(type, null)
                return@withContext
            }

            val thumbnail = runCatching {
                app.contentResolver.loadThumbnail(targetUri, Size(512, 512), null)
            }.getOrNull()

            if (thumbnail != null) {
                updateThumbnailState(type, thumbnail)
                saveMediaUri(type, targetUri.toString())
            } else {
                updateThumbnailState(type, null)
                if (uriOverride == null) saveMediaUri(type, null)
            }
        }
    }

    fun updateThumbnailState(type: MediaType, thumbnail: Bitmap?) {
        _thumbnails.update { currentMap ->
            currentMap + (type to thumbnail)
        }
    }
}