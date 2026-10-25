package com.example.netbox.xray

import android.util.Log

object AppLogger {
    @Volatile
    var isEnabled: Boolean = false

    fun d(tag: String, message: String) {
        if (isEnabled) {
            Log.d(tag, message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (isEnabled) {
            if (throwable != null) {
                Log.e(tag, message, throwable)
            } else {
                Log.e(tag, message)
            }
        }
    }

    fun w(tag: String, message: String) {
        if (isEnabled) {
            Log.w(tag, message)
        }
    }
}
