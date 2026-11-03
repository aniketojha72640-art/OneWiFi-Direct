package com.stealth.onewifi

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val config = WifiP2pConfig.Builder()
                .setNetworkName("DIRECT-00-HP_Print_Service")
                .setPassphrase("NoDishesToday2026")
                .build()

            manager.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Connected to Moto stealth group! Starting VPN...")
                    startVpnTunnel()
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Connect failed: $reason, starting VPN tunnel fallback")
                    startVpnTunnel()
                }
            })
        } else {
            startVpnTunnel()
        }
    }

    private fun startVpnTunnel() {
        try {
            val builder = Builder().apply {
                setSession("OneWiFi-Direct")
                addAddress("10.0.0.2", 24)
                addDnsServer("8.8.8.8")
                setMtu(1500)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setHttpProxy(ProxyInfo.buildDirectProxy("192.168.49.1", 1080))
                }
            }
            vpnInterface = builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN interface: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        wifiP2pManager?.removeGroup(channel, null)
    }
}

