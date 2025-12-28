package com.nothing.camera2magic.viewmodel

import android.content.SharedPreferences
import androidx.compose.runtime.staticCompositionLocalOf

val LocalPrefs = staticCompositionLocalOf<SharedPreferences?> { null }
