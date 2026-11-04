package com.stealth.onewifi

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*

class StealthClientVpnService : VpnService() {

    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var isRunning = false
    @Volatile private var isP2pConnected = false

    private var wifiP2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var connectivityManager: ConnectivityManager? = null
    private var wifiSpecifierCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        private const val TAG = "StealthClientVpnService"
        private const val HOST_PROXY_IP = "192.168.49.1"
    }

    override fun onCreate() {
        super.onCreate()
        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        p2pChannel = wifiP2pManager?.initialize(this, Looper.getMainLooper(), null)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            startAutoConnectRoutine()
        } else {
            // Re-send wake pulse and retry if user tapped search again
            BleWakeManager.sendWakePulse(this)
        }
        return START_STICKY
    }

    private fun startAutoConnectRoutine() {
        clientScope.launch {
            // Wait 2.5 seconds for Moto to receive the BLE wake pulse and create the 5GHz group
            delay(2500L)

            for (attempt in 1..4) {
                if (!isRunning || isP2pConnected) break
                Log.d(TAG, "Auto-connect attempt $attempt to ${StealthHostService.STEALTH_SSID}")
                attemptWifiDirectConnection()
                delay(4000L)
            }

            // Establish the local VPN proxy tunnel once connected (or as fallback)
            if (isRunning && vpnInterface == null) {
                establishProxyVpnTunnel()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun attemptWifiDirectConnection() {
        val manager = wifiP2pManager ?: return
        val ch = p2pChannel ?: return

        // Kick off peer discovery so the Wi-Fi chip scans the 5 GHz channels
        manager.discoverPeers(ch, null)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val config = WifiP2pConfig.Builder()
                .setNetworkName(StealthHostService.STEALTH_SSID)
                .setPassphrase(StealthHostService.STEALTH_PASSPHRASE)
                .build()

            manager.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Wi-Fi Direct connection initiated to Moto host")
                    isP2pConnected = true
                    establishProxyVpnTunnel()
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "Wi-Fi P2P connect attempt returned reason: $reason")
                    if (reason == WifiP2pManager.BUSY) {
                        requestDirectWifiSpecifierFallback()
                    }
                }
            })
        } else {
            establishProxyVpnTunnel()
        }
    }

    private fun requestDirectWifiSpecifierFallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val cm = connectivityManager ?: return
        if (wifiSpecifierCallback != null) return

        try {
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(StealthHostService.STEALTH_SSID)
                .setWpa2Passphrase(StealthHostService.STEALTH_PASSPHRASE)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Connected to Moto group via WifiNetworkSpecifier")
                    isP2pConnected = true
                    cm.bindProcessToNetwork(network)
                    establishProxyVpnTunnel()
                }
            }
            wifiSpecifierCallback = callback
            cm.requestNetwork(request, callback)
        } catch (e: Exception) {
            Log.e(TAG, "WifiNetworkSpecifier fallback error: ${e.message}")
        }
    }

    @Synchronized
    private fun establishProxyVpnTunnel() {
        if (vpnInterface != null) return
        try {
            val builder = Builder()
                .setSession("OneWiFi-Direct")
                .addAddress("10.8.0.2", 24)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .setMtu(1500)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setHttpProxy(
                    ProxyInfo.buildDirectProxy(HOST_PROXY_IP, StealthHostService.PROXY_PORT)
                )
            }

            // Exclude our own app from the VPN loop so control sockets never loop back
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {
            }

            vpnInterface = builder.establish()
            Log.d(TAG, "Samsung VPN Proxy Tunnel active -> $HOST_PROXY_IP:${StealthHostService.PROXY_PORT}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish Samsung VPN Proxy Tunnel: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        isP2pConnected = false
        clientScope.cancel()

        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null

        wifiSpecifierCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
                connectivityManager?.bindProcessToNetwork(null)
            } catch (_: Exception) {
            }
        }
        wifiSpecifierCallback = null

        wifiP2pManager?.removeGroup(p2pChannel, null)
    }
}

