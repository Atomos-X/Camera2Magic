package com.nothing.camera2magic
import android.content.Context

object GlobalState {
    @Volatile
    lateinit var appContext: Context
    @Volatile
    var activityCount = 0

}