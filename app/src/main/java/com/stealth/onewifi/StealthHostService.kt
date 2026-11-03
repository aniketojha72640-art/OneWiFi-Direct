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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class StealthHostService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    @Volatile private var cellularNetwork: Network? = null

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
        requestCellularNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createMundaneNotification())
        BleWakeManager.startHardwareScan(this)

        if (!isRunning) {
            isRunning = true
            startStealthWiFiDirectGroup()
            startDualProxyServer()
        }

        return START_STICKY
    }

    private fun requestCellularNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cm.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cellularNetwork = network
            }
        })
    }

    private fun createMundaneNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelObj = NotificationChannel(
                CHANNEL_ID,
                "System Battery Optimization",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channelObj)
            return Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("System Battery Optimization")
                .setContentText("Normal — running background idle checks")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .build()
        }
        @Suppress("DEPRECATION")
        return Notification.Builder(this)
            .setContentTitle("System Battery Optimization")
            .setContentText("Normal — running background idle checks")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun startStealthWiFiDirectGroup() {
        val manager = wifiP2pManager ?: return
        val ch = channel ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val config = WifiP2pConfig.Builder()
                .setNetworkName("DIRECT-00-HP_Print_Service")
                .setPassphrase("NoDishesToday2026")
                .enablePersistentMode(true)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                .build()

            manager.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Stealth 5GHz Wi-Fi Direct Group live")
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Group create failed: $reason")
                }
            })
        } else {
            manager.createGroup(ch, null)
        }
    }

    private fun startDualProxyServer() {
        serviceScope.launch {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", PROXY_PORT))
                }
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Proxy server error: ${e.message}")
            }
        }
    }

    private suspend fun handleClient(clientSocket: Socket) = withContext(Dispatchers.IO) {
        try {
            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()
            input.mark(1)
            val firstByte = input.read()

            if (firstByte == 0x05) {
                // SOCKS5 Handshake
                val nMethods = input.read()
                input.read(ByteArray(nMethods))
                output.write(byteArrayOf(0x05, 0x00))
                output.flush()

                input.read() // version
                val cmd = input.read()
                input.read() // reserved
                val atyp = input.read()
                if (cmd != 0x01) {
                    clientSocket.close()
                    return@withContext
                }

                val host = when (atyp) {
                    0x01 -> {
                        val b = ByteArray(4)
                        input.read(b)
                        b.joinToString(".") { (it.toInt() and 0xFF).toString() }
                    }
                    0x03 -> {
                        val len = input.read()
                        val b = ByteArray(len)
                        input.read(b)
                        String(b)
                    }
                    else -> {
                        clientSocket.close()
                        return@withContext
                    }
                }
                val p = ByteArray(2)
                input.read(p)
                val port = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)

                val outbound = Socket()
                cellularNetwork?.bindSocket(outbound)
                outbound.connect(InetSocketAddress(host, port), 10000)

                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                pipeSockets(clientSocket, outbound)
            } else {
                // HTTP CONNECT / Proxy Handshake
                val reader = BufferedReader(InputStreamReader(input))
                val firstLine = firstByte.toChar().toString() + (reader.readLine() ?: "")
                val parts = firstLine.split(" ")
                if (parts.size >= 2 && parts[0].equals("CONNECT", ignoreCase = true)) {
                    val hostPort = parts[1].split(":")
                    val host = hostPort[0]
                    val port = if (hostPort.size > 1) hostPort[1].toIntOrNull() ?: 443 else 443
                    while (!reader.readLine().isNullOrEmpty()) { }

                    val outbound = Socket()
                    cellularNetwork?.bindSocket(outbound)
                    outbound.connect(InetSocketAddress(host, port), 10000)

                    output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    output.flush()
                    pipeSockets(clientSocket, outbound)
                }
            }
        } catch (_: Exception) {
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    private suspend fun pipeSockets(client: Socket, outbound: Socket) = coroutineScope {
        val job1 = launch(Dispatchers.IO) {
            try { client.getInputStream().copyTo(outbound.getOutputStream()) } catch (_: Exception) {}
        }
        val job2 = launch(Dispatchers.IO) {
            try { outbound.getInputStream().copyTo(client.getOutputStream()) } catch (_: Exception) {}
        }
        joinAll(job1, job2)
        try { client.close() } catch (_: Exception) {}
        try { outbound.close() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serviceScope.cancel()
        wifiP2pManager?.removeGroup(channel, null)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

