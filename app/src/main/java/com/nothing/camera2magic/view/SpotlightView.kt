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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.nothing.camera2magic.viewmodel.MediaType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotlightView() {
    val factory = LocalViewModelFactory.current
    val viewModel: SpotlightViewModel = viewModel(factory = factory)

    val mediaThumbnails by viewModel.thumbnails.collectAsState()

    val uiState by viewModel.uiState.collectAsState()

    val mediaTypes = stringArrayResource(R.array.media_types)
    var selectedMediaTypeIndex by remember { mutableIntStateOf(0) }

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

            Spacer(modifier = Modifier.height(16.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                mediaTypes.forEachIndexed { index, label ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = mediaTypes.size),
                        onClick = { selectedMediaTypeIndex = index },
                        selected = index == selectedMediaTypeIndex,
                        enabled = label != "rtsp://",
                        icon = {
                            if (label == "Video") {
                                Icon(
                                    imageVector = ImageVector.vectorResource(R.drawable.video_file_24px),
                                    contentDescription = label,
                                    modifier = Modifier.size(SegmentedButtonDefaults.IconSize)
                                )
                            }
                        }) {
                        Text(label)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MediaThumbnailCard(
                    modifier = Modifier.weight(1f),
                    thumbnail = mediaThumbnails[MediaType.VIDEO],
                    onClick = { pickMedia(MediaType.VIDEO) },
                    onClear = { viewModel.clearMedia(MediaType.VIDEO) }
                )
                // 本地图片卡片 (现在功能完整)
                MediaThumbnailCard(
                    modifier = Modifier.weight(1f),
                    thumbnail = mediaThumbnails[MediaType.IMAGE],
                    onClick = { pickMedia(MediaType.IMAGE) },
                    onClear = { viewModel.clearMedia(MediaType.IMAGE) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
            ModuleSwitch(
                text = stringResource(R.string.module_switch_name),
                isChecked = uiState.moduleEnabled,
                onCheckedChange = { viewModel.onModuleToggled() }
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
private fun MediaThumbnailCard(
    modifier: Modifier = Modifier,
    thumbnail: Bitmap?,
    onClick: () -> Unit,
    onClear: () -> Unit
) {
    var isInDeleteMode by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(
                onClick = {
                    if (isInDeleteMode) {
                        isInDeleteMode = false
                    } else {
                        onClick()
                    }
                },
                onLongClick = {
                    if (thumbnail != null) {
                        isInDeleteMode = true
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = "媒体缩略图",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        AnimatedVisibility(
            visible = isInDeleteMode && thumbnail != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.6f),
                                Color.Transparent,
                                Color.Transparent
                            )
                        )
                    )
            ) {
                IconButton(
                    onClick = {
                        onClear()
                        isInDeleteMode = false
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(8.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f))
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
}


@Composable
private fun ModuleSwitch(
    text: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
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
            checked = isChecked,
            onCheckedChange = onCheckedChange,
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