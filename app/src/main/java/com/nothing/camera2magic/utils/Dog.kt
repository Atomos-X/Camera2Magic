package com.nothing.camera2magic.utils
import android.util.Log

object Dog {
    private const val PREFIX = "[VCX]"

    fun i(tag: String? = null, message: String, enabled: Boolean = false) {
        if (!enabled) return
        Log.i("$PREFIX $tag", message)
    }

    fun w(tag: String? = null, message: String, enabled: Boolean = false) {
        if (!enabled) return
        Log.w("$PREFIX $tag", message)
    }

    fun e(tag: String? = null, message: String, throwable: Throwable? = null, enabled: Boolean = false) {
        if (!enabled) return
        if (throwable != null) Log.e("$PREFIX $tag", message, throwable)
        else Log.e("$PREFIX $tag", message)
    }
}