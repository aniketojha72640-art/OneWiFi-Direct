package com.stealth.onewifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class WakeReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "WakeReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "Wake pulse detected by Bluetooth hardware! Waking up Moto host service...")

        val serviceIntent = Intent(context, StealthHostService::class.java).apply {
            action = "ACTION_START_HOST_TUNNEL"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
