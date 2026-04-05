package com.nothing.camera2magic

import android.os.Bundle
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nothing.camera2magic.ui.theme.VirtualCameraXTheme
import com.nothing.camera2magic.view.SettingsView
import com.nothing.camera2magic.view.SpotlightView
import com.google.accompanist.permissions.ExperimentalPermissionsApi
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
                    MainScreen()
                }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar() {
    CenterAlignedTopAppBar(
        title = {
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
        modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            SpotlightView()
            SettingsView()
            Spacer(modifier = Modifier.height(0.dp))
        }
    }
}
