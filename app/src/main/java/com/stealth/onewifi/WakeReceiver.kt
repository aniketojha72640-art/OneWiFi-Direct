package com.stealth.onewifi

import android.bluetooth.le.BluetoothLeScanner
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
        if (intent == null) return

        val errorCode = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, -1)
        if (errorCode != -1) {
            Log.e(TAG, "Hardware BLE scan error code: $errorCode")
            return
        }

        Log.d(TAG, "Samsung BLE wake pulse detected! Starting StealthHostService...")

        val serviceIntent = Intent(context, StealthHostService::class.java).apply {
            action = "ACTION_START_HOST_TUNNEL"
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start StealthHostService from WakeReceiver: ${e.message}")
        }
    }
}
