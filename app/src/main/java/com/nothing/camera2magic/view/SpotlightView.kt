package com.nothing.camera2magic.view

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nothing.camera2magic.viewmodel.SpotlightViewModel
import com.nothing.camera2magic.R
import com.nothing.camera2magic.viewmodel.LocalViewModelFactory
import com.nothing.camera2magic.viewmodel.MediaSource
import com.nothing.camera2magic.viewmodel.MediaType
import kotlin.enums.EnumEntries

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotlightView() {
    val mediaSources = MediaSource.entries
    val mediaSourceLabels = stringArrayResource(R.array.media_source)
    val mediaTypes = MediaType.entries

    val factory = LocalViewModelFactory.current
    val viewModel: SpotlightViewModel = viewModel(factory = factory)

    val mediaThumbnails by viewModel.thumbnails.collectAsState()

    val uiState by viewModel.uiState.collectAsState()

    var pendingType by remember { mutableStateOf<MediaType?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        pendingType?.let { type ->
            viewModel.onMediaSelected(type, it)
        }
    }

    val pickMedia = { type: MediaType ->
        pendingType = type
        launcher.launch(type.mimeType)
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            MediaSourceSelector(
                sources = mediaSources,
                labels = mediaSourceLabels,
                selectedIndex = uiState.selectedMediaSource.value,
                onSourceSelected = { index -> viewModel.selectedMediaSourceFrom(index) }
            )
            Spacer(modifier = Modifier.height(16.dp))
            MediaPreviewGrid(
                mediaTypes = mediaTypes,
                thumbnails = mediaThumbnails,
                currentType = uiState.currentType,
                onPickMedia = { type -> pickMedia(type) },
                onClearMedia = { type -> viewModel.clearMediaBy(type)},
                onTypeSelected = { type -> viewModel.setCurrentMediaType(type) }

            )
            ModuleSwitch(
                text = stringResource(R.string.module_switch_name),
                isEnabled = uiState.moduleEnabled,
                onToggle = { viewModel.onModuleToggled() }
            )
        }
    }
    OnLifecycleEvent { event ->
        if (event == Lifecycle.Event.ON_RESUME) {
            viewModel.performHealthCheckAndRefresh()
        }
    }
}

@Composable
private fun MediaSourceSelector(
    sources: EnumEntries<MediaSource>,
    labels: Array<String>,
    selectedIndex: Int,
    onSourceSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
){
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        sources.forEachIndexed { index, source ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = index, count = sources.size),
                onClick = { onSourceSelected(index) },
                selected = index == selectedIndex,
                enabled = source != MediaSource.NETWORK
            ) {
                Text(labels[index])
            }
        }
    }
}

@Composable
private fun MediaPreviewGrid(
    mediaTypes: EnumEntries<MediaType>,
    thumbnails: Map<MediaType, Bitmap?>,
    currentType: MediaType,
    onPickMedia: (MediaType) -> Unit,
    onClearMedia: (MediaType) -> Unit,
    onTypeSelected: (MediaType) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        mediaTypes.forEach { type ->
            Column(modifier = Modifier.weight(1f)) {
                MediaThumbnailCard(
                    thumbnail = thumbnails[type],
                    mediaType = type,
                    onClick = { onPickMedia(type) },
                    onClear = { onClearMedia(type) }
                )
                RadioButtonRow(
                    selected = currentType == type,
                    onClick = { onTypeSelected(type) },
                )
            }
        }
    }
}

@Composable
private fun MediaThumbnailCard(
    modifier: Modifier = Modifier,
    mediaType: MediaType,
    thumbnail: Bitmap?,
    onClick: () -> Unit,
    onClear: () -> Unit
) {
    var isInDeleteMode by remember { mutableStateOf(false) }

    fun handleOnClick() {
        if (isInDeleteMode) {
            isInDeleteMode = false
        } else {
            onClick()
        }
    }

    fun handleOnLongClick() {
        if (thumbnail != null) {
            isInDeleteMode = true
        }
    }

    fun handleOnClear() {
        onClear()
        isInDeleteMode = false
    }

    Box(
        modifier = modifier
            .aspectRatio(9f / 16f).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(
                onClick = ::handleOnClick,
                onLongClick = ::handleOnLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        ThumbnailContent(thumbnail, mediaType)
        DeleteModeOverlay(isInDeleteMode, ::handleOnClear)
    }
}

@Composable
private fun ThumbnailContent(thumbnail: Bitmap?, mediaType: MediaType) {
    if (thumbnail != null) {
        Image (
            bitmap = thumbnail.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    } else {
        val iconResource = if (mediaType == MediaType.VIDEO) {
            R.drawable.video_file_24px
        } else {
            R.drawable.image_24px
        }
        Image(
            imageVector = ImageVector.vectorResource(iconResource),
            contentDescription = null,
            modifier = Modifier.scale(1.5f)
        )
    }
}

@Composable
private fun DeleteModeOverlay(visible: Boolean, onClear: () -> Unit) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.6f),
                        Color.Transparent,
                        Color.Transparent
                    )
                )
            )
        ){
            IconButton(
                onClick = onClear,
                modifier = Modifier.align(Alignment.Center).padding(8.dp).size(28.dp)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.close_24px),
                    contentDescription = "清除缩略图",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun RadioButtonRow(selected: Boolean, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)

        )
    }
}

@Composable
private fun ModuleSwitch(
    text: String,
    isEnabled: Boolean,
    onToggle: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.developer_board_24px),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            )
        }
    }
}

@Composable
fun OnLifecycleEvent(onEvent: (event: Lifecycle.Event) -> Unit) {
    val eventHandler by rememberUpdatedState(onEvent)
    val lifecycleOwner by rememberUpdatedState(LocalLifecycleOwner.current)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            eventHandler(event)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}