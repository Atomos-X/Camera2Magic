package com.nothing.camera2magic.view

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ControlCamera
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nothing.camera2magic.viewmodel.LocalPrefs
import com.nothing.camera2magic.viewmodel.SettingsViewModel
import com.nothing.camera2magic.viewmodel.ViewModelFactory
import androidx.compose.ui.res.stringResource
import com.nothing.camera2magic.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsView() {
    val context = LocalContext.current
    val prefs = LocalPrefs.current
    val viewModel: SettingsViewModel = viewModel(
        factory = ViewModelFactory(context.applicationContext as Application, prefs)
    )
    val uiState by viewModel.uiState.collectAsState()

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        maxItemsInEachRow = 2,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // "Play sound" 按钮
        SettingsToggleButton(
            modifier = Modifier.weight(1f),
            text = stringResource(R.string.play_sound_button_name),
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            isChecked = uiState.soundEnabled,
            onClick = { viewModel.onPlaySoundToggled() }
        )

        // "Enable Log" 按钮
        SettingsToggleButton(
            modifier = Modifier.weight(1f),
            text = stringResource(R.string.enable_log_button_name),
            icon = Icons.AutoMirrored.Filled.Message,
            isChecked = uiState.logEnabled,
            onClick = { viewModel.onEnableLogToggled() }
        )

        // "Inject Control" 按钮
        SettingsToggleButton(
            modifier = Modifier.weight(1f),
            text = stringResource(R.string.inject_control_button_name),
            icon = Icons.Default.ControlCamera,
            isChecked = uiState.injectMenuEnabled,
            onClick = { viewModel.onInjectMenuToggled() }
        )
        SettingsToggleButton(
            modifier = Modifier.weight(1f),
            text = stringResource(R.string.manually_rotate_button_name),
            icon = Icons.Default.Rotate90DegreesCw,
            isChecked = uiState.manuallyRotateEnabled,
            onClick = { viewModel.onManuallyRotateToggled() }
        )
    }
}

@Composable
private fun SettingsToggleButton(
    modifier: Modifier = Modifier,
    text: String,
    icon: ImageVector,
    isChecked: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (isChecked) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    val contentColor = if (isChecked) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Button(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(minHeight = 58.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 20.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = text,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
