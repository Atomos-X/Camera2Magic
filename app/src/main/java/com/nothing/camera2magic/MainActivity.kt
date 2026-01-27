package com.nothing.camera2magic

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nothing.camera2magic.ui.theme.VirtualCameraXTheme
import com.nothing.camera2magic.view.SettingsView
import com.nothing.camera2magic.view.SpotlightView
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.nothing.camera2magic.viewmodel.ConfigRepository
import com.nothing.camera2magic.viewmodel.LocalViewModelFactory
import com.nothing.camera2magic.viewmodel.ViewModelFactory

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("camera_magic_config", MODE_PRIVATE)

        enableEdgeToEdge()
        setContent {
            val repository = remember { ConfigRepository(prefs) }
            val factory = remember { ViewModelFactory(application, repository) }
            VirtualCameraXTheme(dynamicColor = true) {
                CompositionLocalProvider(LocalViewModelFactory provides factory) {
                    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        listOf(
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VIDEO
                        )
                    } else {
                        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }

                    // 2. 创建并记住权限状态
                    val permissionState = rememberMultiplePermissionsState(permissions)

                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        if(permissionState.allPermissionsGranted) {
                            MainScreen()
                        } else {
                            PermissionRationaleScreen(
                                onGrantPermissionClick = { permissionState.launchMultiplePermissionRequest() }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRationaleScreen(onGrantPermissionClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.permission_rationale_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.permission_rationale_description),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onGrantPermissionClick) {
                Text(text = stringResource(R.string.grant_permission_button_name))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar() {
    // 使用 CenterAlignedTopAppBar 来确保标题内容居中
    CenterAlignedTopAppBar(
        title = {
            // Row 布局用于将图标和文字并排显示
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.icon),
                    contentDescription = "应用Logo",
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Cam2 Magic",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}
@Composable
private fun MainScreen() {
    val scrollState = rememberScrollState()

    Scaffold(topBar = { AppTopBar() },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.statusBarsPadding()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(20.dp) // 使用Arrangement来统一间距
        ) {
            SpotlightView()
            SettingsView()
            Spacer(modifier = Modifier.height(0.dp)) // 可以在底部留空或设置为0
        }
    }
}
