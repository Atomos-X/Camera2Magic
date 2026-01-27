package com.nothing.camera2magic.viewmodel

import androidx.compose.runtime.staticCompositionLocalOf

val LocalConfigRepository = staticCompositionLocalOf<ConfigRepository> {
    error("No ConfigRepository provided")
}
val LocalViewModelFactory = staticCompositionLocalOf<ViewModelFactory> {
    error("No ViewModelFactory provided")
}