package com.example.netbox.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _appLogs = MutableStateFlow<List<String>>(emptyList())
    val appLogs: StateFlow<List<String>> = _appLogs.asStateFlow()

    private val _xrayLogs = MutableStateFlow<List<String>>(emptyList())
    val xrayLogs: StateFlow<List<String>> = _xrayLogs.asStateFlow()

    fun logApp(message: String) {
        val time = timeFormat.format(Date())
        _appLogs.value = _appLogs.value + "[$time] $message"
    }

    fun logXray(message: String) {
        val time = timeFormat.format(Date())
        _xrayLogs.value = _xrayLogs.value + "[$time] $message"
    }

    fun clearAppLogs() {
        _appLogs.value = emptyList()
    }

    fun clearXrayLogs() {
        _xrayLogs.value = emptyList()
    }
}
