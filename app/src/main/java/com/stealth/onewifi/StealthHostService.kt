package com.stealth.onewifi

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class StealthHostService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    @Volatile private var isRunning = false

    private var wifiP2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var cellularNetwork: Network? = null

    private val activeConnections = AtomicInteger(0)
    private val lastActivityTime = AtomicLong(System.currentTimeMillis())
    private var hasClientConnectedOnce = false

    companion object {
        private const val TAG = "StealthHostService"
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "system_battery_opt_channel"
        const val PROXY_PORT = 1080
        const val STEALTH_SSID = "DIRECT-00-HP_Print_Service"
        const val STEALTH_PASSPHRASE = "NoDishesToday2026"
    }

    override fun onCreate() {
        super.onCreate()
        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        p2pChannel = wifiP2pManager?.initialize(this, Looper.getMainLooper(), null)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        requestCellularNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createMundaneNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Ensure hardware BLE standby remains armed for future wakes
        BleWakeManager.startHardwareScan(this)
        lastActivityTime.set(System.currentTimeMillis())

        if (!isRunning) {
            isRunning = true
            startStealthWiFiDirectGroup()
            startDualProxyServer()
            startAutoShutdownMonitor()
        }

        return START_STICKY
    }

    private fun requestCellularNetwork() {
        val cm = connectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cellularNetwork = network
                Log.d(TAG, "Bound to Moto 5G/Cellular network interface")
            }

            override fun onLost(network: Network) {
                if (cellularNetwork == network) {
                    cellularNetwork = null
                }
            }
        }
        networkCallback = callback
        try {
            cm.requestNetwork(request, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Cellular network request error: ${e.message}")
        }
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
                setShowBadge(false)
            }
            manager.createNotificationChannel(channelObj)
            return Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("System Battery Optimization")
                .setContentText("Normal — running background idle checks")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setOngoing(true)
                .build()
        }

        @Suppress("DEPRECATION")
        return Notification.Builder(this)
            .setContentTitle("System Battery Optimization")
            .setContentText("Normal — running background idle checks")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun startStealthWiFiDirectGroup() {
        val manager = wifiP2pManager ?: return
        val ch = p2pChannel ?: return

        // Clear any stale group first before spinning up the fresh stealth group
        manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                createNewP2pGroup(manager, ch)
            }

            override fun onFailure(reason: Int) {
                createNewP2pGroup(manager, ch)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun createNewP2pGroup(manager: WifiP2pManager, ch: WifiP2pManager.Channel) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val config5Ghz = WifiP2pConfig.Builder()
                .setNetworkName(STEALTH_SSID)
                .setPassphrase(STEALTH_PASSPHRASE)
                .enablePersistentMode(true)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                .build()

            manager.createGroup(ch, config5Ghz, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "5 GHz Stealth Wi-Fi Direct group active: $STEALTH_SSID")
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "5 GHz band busy ($reason), falling back to Auto band")
                    val configAuto = WifiP2pConfig.Builder()
                        .setNetworkName(STEALTH_SSID)
                        .setPassphrase(STEALTH_PASSPHRASE)
                        .enablePersistentMode(true)
                        .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                        .build()
                    manager.createGroup(ch, configAuto, null)
                }
            })
        } else {
            manager.createGroup(ch, null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAutoShutdownMonitor() {
        serviceScope.launch {
            while (isRunning) {
                delay(10_000L)
                val manager = wifiP2pManager
                val ch = p2pChannel
                if (manager != null && ch != null) {
                    manager.requestGroupInfo(ch) { group ->
                        if (group != null && group.clientList.isNotEmpty()) {
                            hasClientConnectedOnce = true
                            lastActivityTime.set(System.currentTimeMillis())
                        }
                    }
                }

                val idleMillis = System.currentTimeMillis() - lastActivityTime.get()
                val timeoutMillis = if (hasClientConnectedOnce) 35_000L else 95_000L

                if (activeConnections.get() <= 0 && idleMillis > timeoutMillis) {
                    Log.d(TAG, "Samsung disconnected or idle — shutting down host to save battery")
                    stopSelf()
                    break
                }
            }
        }
    }

    private fun startDualProxyServer() {
        serviceScope.launch {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", PROXY_PORT))
                }
                Log.d(TAG, "Dual SOCKS5/HTTP Proxy listening on 0.0.0.0:$PROXY_PORT")

                while (isRunning) {
                    val client = try {
                        serverSocket?.accept()
                    } catch (e: Exception) {
                        break
                    } ?: break

                    launch { handleProxyClient(client) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Proxy server socket error: ${e.message}")
            }
        }
    }

    private suspend fun handleProxyClient(clientSocket: Socket) = withContext(Dispatchers.IO) {
        activeConnections.incrementAndGet()
        lastActivityTime.set(System.currentTimeMillis())

        try {
            clientSocket.soTimeout = 30_000
            val input = BufferedInputStream(clientSocket.getInputStream())
            val output = clientSocket.getOutputStream()

            val firstByte = input.read()
            if (firstByte == -1) {
                clientSocket.close()
                return@withContext
            }

            if (firstByte == 0x05) {
                handleSocks5Client(clientSocket, input, output)
            } else {
                handleHttpProxyClient(clientSocket, firstByte, input, output)
            }
        } catch (e: Exception) {
            try {
                clientSocket.close()
            } catch (_: Exception) {
            }
        } finally {
            activeConnections.decrementAndGet()
            lastActivityTime.set(System.currentTimeMillis())
        }
    }

    private suspend fun handleSocks5Client(
        clientSocket: Socket,
        input: InputStream,
        output: java.io.OutputStream
    ) {
        val nMethods = input.read()
        if (nMethods > 0) {
            val methods = ByteArray(nMethods)
            input.read(methods)
        }

        // Reply: SOCKS5, No Authentication (0x00)
        output.write(byteArrayOf(0x05, 0x00))
        output.flush()

        val version = input.read()
        val cmd = input.read()
        input.read() // reserved byte
        val atyp = input.read()

        if (version != 0x05 || cmd != 0x01) {
            clientSocket.close()
            return
        }

        val targetHost: String = when (atyp) {
            0x01 -> {
                val addr = ByteArray(4)
                input.read(addr)
                addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            0x03 -> {
                val len = input.read()
                val domain = ByteArray(len)
                input.read(domain)
                String(domain)
            }
            0x04 -> {
                val addr = ByteArray(16)
                input.read(addr)
                clientSocket.close()
                return
            }
            else -> {
                clientSocket.close()
                return
            }
        }

        val portBytes = ByteArray(2)
        input.read(portBytes)
        val targetPort =
            ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

        val outbound = openCellularSocket(targetHost, targetPort)
        output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()

        pipeSockets(clientSocket, input, outbound)
    }

    private suspend fun handleHttpProxyClient(
        clientSocket: Socket,
        firstByte: Int,
        input: InputStream,
        output: java.io.OutputStream
    ) {
        val firstLine = firstByte.toChar().toString() + readAsciiLine(input)
        val parts = firstLine.trim().split(" ")
        if (parts.size < 2) {
            clientSocket.close()
            return
        }

        val method = parts[0]
        val rawTarget = parts[1]

        // Read all remaining HTTP header lines until blank line
        val headerLines = mutableListOf<String>()
        while (true) {
            val line = readAsciiLine(input).trimEnd('\r', '\n')
            if (line.isEmpty()) break
            headerLines.add(line)
        }

        if (method.equals("CONNECT", ignoreCase = true)) {
            val hostPort = rawTarget.split(":")
            val host = hostPort[0]
            val port = if (hostPort.size > 1) hostPort[1].toIntOrNull() ?: 443 else 443

            val outbound = openCellularSocket(host, port)
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            output.flush()

            pipeSockets(clientSocket, input, outbound)
        } else {
            val uri = URI(rawTarget)
            val host = uri.host ?: run {
                clientSocket.close()
                return
            }
            val port = if (uri.port != -1) uri.port else 80
            val pathAndQuery = buildString {
                append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
                if (!uri.rawQuery.isNullOrEmpty()) {
                    append("?").append(uri.rawQuery)
                }
            }

            val outbound = openCellularSocket(host, port)
            val outStream = outbound.getOutputStream()

            val rewrittenRequest = buildString {
                append("$method $pathAndQuery ${parts.getOrElse(2) { "HTTP/1.1" }}\r\n")
                for (h in headerLines) {
                    if (!h.startsWith("Proxy-", ignoreCase = true)) {
                        append("$h\r\n")
                    }
                }
                append("\r\n")
            }
            outStream.write(rewrittenRequest.toByteArray())
            outStream.flush()

            pipeSockets(clientSocket, input, outbound)
        }
    }

    private fun openCellularSocket(host: String, port: Int): Socket {
        val outbound = Socket()
        try {
            cellularNetwork?.bindSocket(outbound)
        } catch (e: Exception) {
            Log.w(TAG, "Fallback to default routing for socket: ${e.message}")
        }
        outbound.connect(InetSocketAddress(host, port), 12_000)
        return outbound
    }

    private fun readAsciiLine(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            if (b != '\r'.code) {
                buffer.write(b)
            }
        }
        return buffer.toString(Charsets.UTF_8.name())
    }

    private suspend fun pipeSockets(
        client: Socket,
        clientInput: InputStream,
        outbound: Socket
    ) = coroutineScope {
        val clientToOut = launch(Dispatchers.IO) {
            try {
                val buf = ByteArray(16 * 1024)
                val outStream = outbound.getOutputStream()
                while (isActive) {
                    val read = clientInput.read(buf)
                    if (read == -1) break
                    outStream.write(buf, 0, read)
                    outStream.flush()
                    lastActivityTime.set(System.currentTimeMillis())
                }
            } catch (_: Exception) {
            } finally {
                try {
                    outbound.shutdownOutput()
                } catch (_: Exception) {
                }
            }
        }

        val outToClient = launch(Dispatchers.IO) {
            try {
                val buf = ByteArray(16 * 1024)
                val inStream = outbound.getInputStream()
                val clientOut = client.getOutputStream()
                while (isActive) {
                    val read = inStream.read(buf)
                    if (read == -1) break
                    clientOut.write(buf, 0, read)
                    clientOut.flush()
                    lastActivityTime.set(System.currentTimeMillis())
                }
            } catch (_: Exception) {
            } finally {
                try {
                    client.shutdownOutput()
                } catch (_: Exception) {
                }
            }
        }

        joinAll(clientToOut, outToClient)
        try {
            client.close()
        } catch (_: Exception) {
        }
        try {
            outbound.close()
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        wifiP2pManager?.removeGroup(p2pChannel, null)
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

