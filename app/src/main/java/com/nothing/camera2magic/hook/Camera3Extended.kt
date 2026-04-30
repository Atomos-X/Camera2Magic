package com.nothing.camera2magic.hook

import android.os.Handler
import android.os.HandlerThread

object Camera3Extended {
    private var _handlerThread: HandlerThread? = null
    private var _handler: Handler? = null

    @get:Synchronized
    val handler: Handler get() {
        if (_handler == null || !_handlerThread!!.isAlive) {
            _handlerThread = HandlerThread("Camera3").apply { start() }
            _handler = Handler(_handlerThread!!.looper)
        }
        return _handler!!
    }

    @Synchronized
    fun release() {
        _handlerThread?.quitSafely()
        _handlerThread = null
        _handler = null
    }
}