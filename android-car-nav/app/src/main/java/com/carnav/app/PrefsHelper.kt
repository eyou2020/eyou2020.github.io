package com.carnav.app

import android.content.Context

object PrefsHelper {
    private const val PREFS_NAME = "car_nav_prefs"
    private const val KEY_TARGET_ADDRESS = "target_device_address"
    private const val KEY_TARGET_NAME = "target_device_name"
    private const val KEY_SERVICE_ENABLED = "service_enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveTargetDevice(context: Context, address: String, name: String) {
        prefs(context).edit()
            .putString(KEY_TARGET_ADDRESS, address)
            .putString(KEY_TARGET_NAME, name)
            .apply()
    }

    fun getTargetDeviceAddress(context: Context): String? =
        prefs(context).getString(KEY_TARGET_ADDRESS, null)

    fun getTargetDeviceName(context: Context): String? =
        prefs(context).getString(KEY_TARGET_NAME, null)

    fun setServiceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
    }

    fun isServiceEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SERVICE_ENABLED, false)
}
