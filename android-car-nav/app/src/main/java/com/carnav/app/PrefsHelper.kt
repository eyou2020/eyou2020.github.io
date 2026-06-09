package com.carnav.app

import android.content.Context

object PrefsHelper {
    private const val PREFS_NAME = "car_nav_prefs"
    private const val KEY_DEVICE_NAME = "bt_device_name"
    private const val KEY_SERVICE_ENABLED = "service_enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getDeviceName(context: Context): String =
        prefs(context).getString(KEY_DEVICE_NAME, "Car Navigation") ?: "Car Navigation"

    fun setDeviceName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_DEVICE_NAME, name.trim().ifEmpty { "Car Navigation" }).apply()
    }

    fun setServiceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
    }

    fun isServiceEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SERVICE_ENABLED, false)
}
