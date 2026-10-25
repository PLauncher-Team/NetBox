package com.example.netbox.xray

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

object AppLogger {
    @Volatile
    var isEnabled: Boolean = false

    private const val MAX_ENTRIES = 5000
    private val entries = ConcurrentLinkedDeque<String>()

    private fun record(level: String, tag: String, message: String) {
        val line = "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} $level/$tag: $message"
        entries.addLast(line)
        while (entries.size > MAX_ENTRIES) entries.pollFirst()
    }

    fun exportText(): String = entries.joinToString(separator = "\n")

    fun d(tag: String, message: String) {
        record("D", tag, message)
        if (isEnabled) Log.d(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        record("E", tag, if (throwable == null) message else "$message\n${Log.getStackTraceString(throwable)}")
        if (isEnabled) {
            if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
        }
    }

    fun w(tag: String, message: String) {
        record("W", tag, message)
        if (isEnabled) Log.w(tag, message)
    }
}
