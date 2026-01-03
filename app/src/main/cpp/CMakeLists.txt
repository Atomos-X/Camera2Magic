cmake_minimum_required(VERSION 3.18.1)
project("camera_magic" CXX)
set(CMAKE_ANDROID_API 29)

# set(CMAKE_ANDROID_ARCH_ABI ${ANDROID_ABI})
# set(OUTPUT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${CMAKE_ANDROID_ARCH_ABI})
# message(STATUS "Output directory for ${ANDROID_ABI} is: ${OUTPUT_DIR}")
# file(MAKE_DIRECTORY ${OUTPUT_DIR})
#
include_directories(${CMAKE_SOURCE_DIR}/ffmpeg/include)
set(FFMPEG_LIB_DIR ${CMAKE_SOURCE_DIR}/ffmpeg/libs/${ANDROID_ABI})

# FFmpeg (STATIC IMPORTED)
add_library(avcodec STATIC IMPORTED)
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavcodec.a)

add_library(avformat STATIC IMPORTED)
set_target_properties(avformat PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavformat.a)

add_library(avutil STATIC IMPORTED)
set_target_properties(avutil PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavutil.a)

add_library(swresample STATIC IMPORTED)
set_target_properties(swresample PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libswresample.a)

add_library(
        camera_magic
        SHARED
        platform_magic.cpp
        render/texture_copier.cpp
        render/double_buffer.cpp
        buffer/pbo_buffer.cpp
        yuv/yuv_converter.cpp
        yuv/async_yuv_converter.cpp
        utils/matrix_utils.cpp
        camera/camera_state.cpp
        surface/surface_texture.cpp
        media/media_handle.cpp
        egl/egl_context.cpp
        utils/magic_config.cpp
)

#set_target_properties(
#        camera_magic
#        PROPERTIES
#        LIBRARY_OUTPUT_DIRECTORY "${OUTPUT_DIR}"
#)

target_link_libraries(
        camera_magic
        avformat
        avcodec
        # swresample
        avutil
        android
        EGL
        GLESv3
        log
        mediandk
        aaudio
        z
)
