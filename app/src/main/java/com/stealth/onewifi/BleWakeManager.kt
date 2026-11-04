package com.stealth.onewifi

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

object BleWakeManager {
    val WAKE_UUID: UUID = UUID.fromString("0000BEEF-0000-1000-8000-00805F9B34FB")
    const val ACTION_BLE_WAKE = "com.stealth.onewifi.ACTION_BLE_WAKE"
    private const val TAG = "BleWakeManager"

    @SuppressLint("MissingPermission")
    fun sendWakePulse(context: Context) {
        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter ?: return
        val advertiser = bluetoothAdapter.bluetoothLeAdvertiser

        if (advertiser == null) {
            Log.e(TAG, "BLE Advertiser not supported or Bluetooth is turned off")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(3000) // Broadcast for 3 seconds then stop automatically
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(WAKE_UUID))
            .setIncludeDeviceName(false)
            .build()

        try {
            advertiser.startAdvertising(settings, data, object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    Log.d(TAG, "3-second BLE wake pulse sent to Moto host")
                }

                override fun onStartFailure(errorCode: Int) {
                    Log.e(TAG, "BLE wake pulse failed with code: $errorCode")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error transmitting BLE wake pulse: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun startHardwareScan(context: Context) {
        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter ?: return
        val scanner = bluetoothAdapter.bluetoothLeScanner

        if (scanner == null) {
            Log.e(TAG, "BLE Scanner unavailable — make sure Bluetooth is ON on the Moto")
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(WAKE_UUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .build()

        try {
            scanner.startScan( listOf(filter), scanSettings, getWakePendingIntent(context))
            Log.d(TAG, "Zero-battery hardware BLE scan armed on Moto")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register hardware BLE scan: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopHardwareScan(context: Context) {
        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter ?: return
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return

        try {
            scanner.stopScan(getWakePendingIntent(context))
            Log.d(TAG, "Hardware BLE scan stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop hardware BLE scan: ${e.message}")
        }
    }

    private fun getWakePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WakeReceiver::class.java).apply {
            action = ACTION_BLE_WAKE
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, 707, intent, flags)
    }
}
