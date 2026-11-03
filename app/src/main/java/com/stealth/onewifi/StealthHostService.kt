package com.stealth.onewifi

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class StealthHostService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    companion object {
        private const val TAG = "StealthHostService"
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "stealth_service_channel"
        private const val PROXY_PORT = 1080
    }

    override fun onCreate() {
        super.onCreate()
        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = wifiP2pManager?.initialize(this, Looper.getMainLooper(), null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createMundaneNotification())
        BleWakeManager.startHardwareScan(this)

        if (!isRunning) {
            isRunning = true
            startStealthWiFiDirectGroup()
            startSocks5ProxyServer()
        }

        return START_STICKY
    }

    private fun createMundaneNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelObj = NotificationChannel(
                CHANNEL_ID,
                "System Battery Optimization",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors background power states"
            }
            manager.createNotificationChannel(channelObj)
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("System Battery Optimization")
                .setContentText("Normal — running background idle checks")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("System Battery Optimization")
                .setContentText("Normal — running background idle checks")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .build()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startStealthWiFiDirectGroup() {
        val manager = wifiP2pManager ?: return
        val ch = channel ?: return

        val config = WifiP2pConfig.Builder()
            .setNetworkName("DIRECT-00-HP_Print_Service") // Looks like a boring printer
            .setPassphrase("NoDishesToday2026")
            .enablePersistentMode(true)
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
            .build()

        manager.createGroup(ch, config, object : WifiP2pManager.ActionListener {
            onSuccess() {
                Log.d(TAG, "Stealth 5GHz Wi-Fi Direct Autonomous Group created successfully")
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to create Wi-Fi Direct group, reason code: $reason")
            }
        })
    }

    private fun startSocks5ProxyServer() {
        serviceScope.launch {
            try {
                serverSocket = ServerSocket().apply {
                    bind(InetSocketAddress("192.168.49.1", PROXY_PORT))
                }
                Log.d(TAG, "SOCKS5 Proxy server running on 192.168.49.1:$PROXY_PORT")

                whileD@ while (isRunning) {
                    val clientSocket = try {
                        serverSocket?.accept()
                    } catch (e: Exception) {
                        break@whileD
                    }

                    if (clientSocket != null) {
                        launch { handleClientConnection(clientSocket) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SOCKS5 Server error: ${e.message}")
            }
        }
    }

    private suspend fun handleClientConnection(clientSocket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                val input = clientSocket.getInputStream()
                val output = clientSocket.getOutputStream()

                // Read SOCKS5 greeting
                val version = input.read()
                val nMethods = input.read()
                val methods = ByteArray(nMethods)
                input.read(methods)

                // Respond with no authentication required
                output.write(byteArrayOf(0x05, 0x00))
                output.flush()

                // Read connection request
                val reqVersion = input.read()
                val cmd = input.read()
                val reserved = input.read()
                val atyp = input.read()

                if (cmd != 0x01) { // Only handle CONNECT command
                    clientSocket.close()
                    return@withContext
                }

                val host: String
                when (atyp) {
                    0x01 -> { // IPv4
                        val addrBytes = ByteArray(4)
                        input.read(addrBytes)
                        host = addrBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
                    }
                    0x03 -> { // Domain name
                        val len = input.read()
                        val domainBytes = ByteArray(len)
                        input.read(domainBytes)
                        host = String(domainBytes)
                    }
                    0x04 -> { // IPv6
                        val addrBytes = ByteArray(16)
                        input.read(addrBytes)
                        host = "::1" // Simplified fallback
                    }
                    else -> {
                        clientSocket.close()
                        return@withContext
                    }
                }

                val portBytes = ByteArray(2)
                input.read(portBytes)
                val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

                // Create outbound socket bound explicitly to Moto's 5G Cellular Data
                val outboundSocket = Socket()
                bindSocketToCellular(outboundSocket)
                outboundSocket.connect(InetSocketAddress(host, port), 10000)

                // Send SOCKS5 success reply
                val reply = byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)
                output.write(reply)
                output.flush()

                // Bidirectional pipe streaming
                launch {
                    try {
                        clientSocket.getInputStream().copyTo(outboundSocket.getOutputStream())
                    } catch (_: Exception) {}
                }
                outboundSocket.getInputStream().copyTo(clientSocket.getOutputStream())

            } catch (e: Exception) {
                Log.e(TAG, "Proxy pipe error: ${e.message}")
            } finally {
                try { clientSocket.close() } catch (_: Exception) {}
            }
        }
    }

    private fun bindSocketToCellular(socket: Socket) {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cm.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                network.bindSocket(socket)
                cm.unregisterNetworkCallback(this)
            }
        })
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serviceScope.cancel()
        BleWakeManager.stopHardwareScan(this)
        wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {}
        })
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
