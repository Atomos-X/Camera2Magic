#ifndef CAMERA2_MAGIC_LOG_UTILS_H
#define CAMERA2_MAGIC_LOG_UTILS_H

#pragma once
#include <android/log.h>
#include "magic_config.h"
#include <cstdarg>

#define LOG_PREFIX "[VCX]"
#define LOGI(TAG, ...) if (g_config.enable_log) { __android_log_print(ANDROID_LOG_INFO, LOG_PREFIX TAG, __VA_ARGS__); }
// #define LOGW(TAG, ...) if (g_config.enable_log) { __android_log_print(ANDROID_LOG_WARN, LOG_PREFIX TAG, __VA_ARGS__); }
#define LOGW(TAG,...) __android_log_print(ANDROID_LOG_WARN, LOG_PREFIX TAG, __VA_ARGS__)
#define LOGE(TAG,...) __android_log_print(ANDROID_LOG_ERROR, LOG_PREFIX TAG, __VA_ARGS__)

#endif //CAMERA2_MAGIC_LOG_UTILS_H
