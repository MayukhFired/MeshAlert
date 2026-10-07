package com.meshalert.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meshalert.app.service.MeshForegroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(Intent(context, MeshForegroundService::class.java))
        }
    }
}
