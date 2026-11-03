package com.stealth.onewifi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.os.ParcelUuid
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

object BleWakeManager {
    // Shared secret UUID for the stealth wake handshake
    val WAKE_UUID: UUID = UUID.fromString("0000BEEF-0000-1000-8000-00805F9B34FB")
    private const val TAG = "BleWakeManager"

    @SuppressLint("MissingPermission")
    fun sendWakePulse(context: Context) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter ?: return
        val advertiser = bluetoothAdapter.bluetoothLeAdvertiser

        if (advertiser == null) {
            Log.e(TAG, "BLE Advertiser not available on this device")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(false)
            .setTimeout(3000) // Pulse for 3 seconds then shut down to save battery
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(WAKE_UUID))
            .setIncludeDeviceName(false)
            .build()

        advertiser.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d(TAG, "Wake pulse transmitted successfully")
            }
            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Wake pulse failed with error: $errorCode")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun startHardwareScan(context: Context) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter ?: return
        val scanner = bluetoothAdapter.bluetoothLeScanner

        if (scanner == null) {
            Log.e(TAG, "BLE Scanner not available on Moto host")
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(WAKE_UUID))
            .build()

        val filters = listOf(filter)

        val scanSettings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        val intent = Intent(context, WakeReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Offload scanning entirely to the Bluetooth hardware chipset (zero CPU usage!)
        try {
            scanner.startScan(filters, scanSettings, pendingIntent)
            Log.d(TAG, "Hardware BLE scan registered successfully on Moto")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start hardware scan: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopHardwareScan(context: Context) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter ?: return
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return

        val intent = Intent(context, WakeReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            scanner.stopScan(pendingIntent)
            Log.d(TAG, "Hardware BLE scan stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop hardware scan: ${e.message}")
        }
    }
}
