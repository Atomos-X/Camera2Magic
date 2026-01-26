package com.nothing.camera2magic.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.io.File
import kotlin.io.path.exists

/**
 * breaking update:
 * use main_config.xml save:
 *   - module_enabled: Boolean = true, // 是否启用模块
 *   - play_sound: Boolean = false, // 是否播放声音
 *   - enable_log: Boolean = false, // 是否启用日志
 *   - inject_menu: Boolean = false, // 是否注入(浮动)菜单
 *   - manually_rotate: Boolean = false, // 是否手动旋转画面(90度)
 *
 * media_config.xml save:
 *   - media_type: Int = 0, // 0: Local Media, 1: Network Media
 *
 * local_media.xml save:
 *   - current_media: Int = 0, // 0: Video, 1: Image
 *   - video_id: Long = -1, // 视频ID, -1: 未选择; 由 String? -> Long 存储
 *   - image_id: Long = -1, // 图片ID, -1: 未选择; 由 String? -> Long 存储
 *
 * network_media.xml save:
 *   - network_media_type: Int = 0, // 0: rtsp,
 */
data class MediaSourceConfig (
    val local: SharedPreferences, // local_media.xml
    val network: SharedPreferences // network_media.xml
)

data class SpotlightConfig (
    val mediaPrefs: SharedPreferences, // media_config.xml
    val mediaSource: MediaSourceConfig
)

@Suppress("UNCHECKED_CAST")
class ViewModelFactory(private val app: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val mainPrefs = getSafePrefs("main_config")
        return when {
            modelClass.isAssignableFrom(SpotlightViewModel::class.java) -> {
                val mediaPrefs = getSafePrefs("media_config")


                SpotlightViewModel(app, mediaPrefs) as T
            }
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> {
                SettingsViewModel(app, mainPrefs) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }

    }

    /**
     * 获取 SharedPreferences 的安全封装
     * 兼容性方案：优先尝试系统 API，失败则手动修复磁盘权限
     */
    private fun getSafePrefs(name: String): SharedPreferences? {
        return try {
            // 在模块激活时能直接创建
            app.getSharedPreferences(name, Context.MODE_WORLD_READABLE)
        } catch (e: SecurityException) {
            null
        }
    }
}
