package com.carnav.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && PrefsHelper.isServiceEnabled(context)) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, HfpServerService::class.java)
            )
        }
    }
}
