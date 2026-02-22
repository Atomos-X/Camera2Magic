package com.nothing.camera2magic.utils

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.nothing.camera2magic.GlobalState

object MsgUtils {
    private val handler = Handler(Looper.getMainLooper())

    @JvmStatic
    fun show(message: String) {
        handler.post {
            Toast.makeText(GlobalState.appContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}