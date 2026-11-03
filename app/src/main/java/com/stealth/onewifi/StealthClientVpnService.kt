package com.stealth.onewifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel

class StealthClientVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    companion object {
        private const val TAG = "StealthClientVpnService"
    }

    override fun onCreate() {
        super.onCreate()
        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = wifiP2pManager?.initialize(this, mainLooper, null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            connectToStealthGroup()
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun connectToStealthGroup() {
        val manager = wifiP2pManager ?: return
        val ch = channel ?: return

        manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Wi-Fi Direct peer discovery initiated for stealth connection")
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Peer discovery failed: $reason")
            }
        })

        // Attempt connection to the disguised printer SSID
        val config = WifiP2pConfig().apply {
            deviceAddress = "DIRECT-00-HP_Print_Service"
            passphrase = "NoDishesToday2026"
        }

        manager.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Connected to Moto stealth group! Starting local VPN tunnel...")
                startVpnTunnel()
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to connect to group, reason: $reason")
            }
        })
    }

    private fun startVpnTunnel() {
        try {
            val builder = Builder().apply {
                setSession("OneWiFi-Direct")
                addAddress("10.0.0.2", 24)
                addRoute("0.0.0.0", 0) // Intercept all device outbound traffic
                addDnsServer("8.8.8.8")
                setMtu(1500)
            }

            vpnInterface = builder.establish()

            // Start tun2socks packet routing pipeline to Moto's proxy server
            Thread {
                runTun2SocksLoop(vpnInterface?.fileDescriptor)
            }.start()

            Log.d(TAG, "Samsung VPN tunnel successfully established and routing packets.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN interface: ${e.message}")
        }
    }

    private fun runTun2SocksLoop(fileDescriptor: java.io.FileDescriptor?) {
        if (fileDescriptor == null) return
        try {
            // Initialize tun2socks bridge pointing to Moto proxy server
            // Traffic flows: Samsung Apps -> VPN Tun -> tun2socks -> SOCKS5 -> Moto 5G Data
            com.github.tun2socks.Tun2socks.ksi(
                fileDescriptor.setInt(fileDescriptor),
                1500,
                "socks5://192.168.49.1:1080",
                true,
                false
            )
        } catch (e: Exception) {
            Log.e(TAG, "tun2socks error: ${e.message}")
        }
    }

    // Helper reflection bypass to extract file descriptor integer for AAR binding
    private fun java.io.FileDescriptor.setInt(fd: java.io.FileDescriptor): Int {
        val field = java.io.FileDescriptor::class.java.getDeclaredField("descriptor")
        field.isAccessible = true
        return field.getInt(fd)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null
        wifiP2pManager?.removeGroup(channel, null)
    }
}
