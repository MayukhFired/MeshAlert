package com.meshalert.app.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.p2p.*
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Full Wi-Fi Direct (P2P) Transport Pipeline for MeshAlert:
 * - Manages peer discovery and group owner negotiation.
 * - Handles connection lifecycle via WifiP2p BroadcastReceiver.
 * - Manages concurrent ServerSocket and client Socket streaming on port 8988.
 * - Streams high-capacity payloads (full JSON messages) across the offline mesh.
 */
class WifiDirectManager(
    private val context: Context,
    private val onMessageReceived: (String) -> Unit
) {

    companion object {
        private const val TAG = "WifiDirectManager"
        const val PORT = 8988
        private const val SOCKET_TIMEOUT_MS = 5000
    }

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? =
        manager?.initialize(context, context.mainLooper, null)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null

    // State Tracking
    private val _isWifiP2pEnabled = MutableStateFlow(false)
    val isWifiP2pEnabled: StateFlow<Boolean> = _isWifiP2pEnabled.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _peerList = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val peerList: StateFlow<List<WifiP2pDevice>> = _peerList.asStateFlow()

    private var isGroupOwner = false
    private var groupOwnerAddress: String? = null
    private val connectedClients = ConcurrentHashMap.newKeySet<String>()

    private val p2pReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    _isWifiP2pEnabled.value = enabled
                    Log.d(TAG, "Wi-Fi P2P state changed: enabled=$enabled")
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (hasWifiPermissions()) {
                        manager?.requestPeers(channel) { peers ->
                            val list = peers.deviceList.toList()
                            _peerList.value = list
                            Log.d(TAG, "Wi-Fi Direct peers discovered: ${list.size} devices")
                            autoConnectIfAvailable(list)
                        }
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }

                    if (networkInfo?.isConnected == true) {
                        manager?.requestConnectionInfo(channel) { info ->
                            handleConnectionInfo(info)
                        }
                    } else {
                        Log.d(TAG, "Wi-Fi Direct disconnected")
                        _isConnected.value = false
                        isGroupOwner = false
                        groupOwnerAddress = null
                        connectedClients.clear()
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                    Log.d(TAG, "This device P2P status: ${device?.status}")
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            context,
            p2pReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        startServer()
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        if (!hasWifiPermissions() || manager == null || channel == null) return
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Wi-Fi Direct peer discovery initiated")
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "Wi-Fi Direct peer discovery failed (reason: $reason)")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice) {
        if (!hasWifiPermissions() || manager == null || channel == null) return
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Connecting to Wi-Fi Direct peer: ${device.deviceName} (${device.deviceAddress})")
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "Failed connecting to peer (reason: $reason)")
            }
        })
    }

    private fun autoConnectIfAvailable(devices: List<WifiP2pDevice>) {
        if (_isConnected.value) return
        // Connect to the first available (unpaired/invited) peer
        val available = devices.firstOrNull { it.status == WifiP2pDevice.AVAILABLE }
        if (available != null) {
            connect(available)
        }
    }

    private fun handleConnectionInfo(info: WifiP2pInfo) {
        if (!info.groupFormed) return
        _isConnected.value = true
        isGroupOwner = info.isGroupOwner
        groupOwnerAddress = info.groupOwnerAddress?.hostAddress

        Log.i(TAG, "Wi-Fi Direct Connected! GroupOwner=${isGroupOwner}, Host=${groupOwnerAddress}")

        if (isGroupOwner) {
            // Group owner keeps server running to accept incoming client sockets
            startServer()
        }
    }

    fun startServer() {
        if (serverJob?.isActive == true) return

        serverJob = scope.launch(Dispatchers.IO) {
            runCatching {
                serverSocket?.close()
                val server = ServerSocket(PORT)
                serverSocket = server
                Log.i(TAG, "Wi-Fi Direct ServerSocket listening on port $PORT")

                while (isActive && !server.isClosed) {
                    val client = server.accept()
                    val clientIp = client.inetAddress?.hostAddress
                    clientIp?.let { connectedClients.add(it) }

                    launch(Dispatchers.IO) {
                        handleClientConnection(client)
                    }
                }
            }.onFailure {
                if (isActive) Log.e(TAG, "ServerSocket error", it)
            }
        }
    }

    private suspend fun handleClientConnection(socket: Socket) = withContext(Dispatchers.IO) {
        runCatching {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val message = reader.readText()
            socket.close()

            if (message.isNotBlank()) {
                Log.d(TAG, "Received message over Wi-Fi Direct: ${message.take(50)}...")
                onMessageReceived(message)
            }
        }.onFailure {
            Log.w(TAG, "Error reading from client socket", it)
        }
    }

    /**
     * Broadcasts a message to the Wi-Fi Direct mesh:
     * - If client: sends to the Group Owner.
     * - If Group Owner: fans out to all known client addresses.
     */
    suspend fun broadcastMessage(message: String): Boolean = withContext(Dispatchers.IO) {
        if (!_isConnected.value) return@withContext false

        var delivered = false
        if (!isGroupOwner && groupOwnerAddress != null) {
            // Client sending to Group Owner
            delivered = sendMessageToHost(groupOwnerAddress!!, message)
        } else if (isGroupOwner) {
            // Group owner broadcasting to all known clients
            for (clientIp in connectedClients) {
                val ok = sendMessageToHost(clientIp, message)
                if (ok) delivered = true
            }
        }
        return@withContext delivered
    }

    suspend fun sendMessage(hostAddress: String, message: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext sendMessageToHost(hostAddress, message)
    }

    private fun sendMessageToHost(hostAddress: String, message: String): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(hostAddress, PORT), SOCKET_TIMEOUT_MS)
                val writer = OutputStreamWriter(socket.getOutputStream())
                writer.write(message)
                writer.flush()
                Log.d(TAG, "Successfully sent message to Wi-Fi Direct host: $hostAddress")
                true
            }
        }.getOrElse {
            Log.w(TAG, "Failed to send message to $hostAddress", it)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        if (!hasWifiPermissions() || manager == null || channel == null) return
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Wi-Fi Direct group removed")
                _isConnected.value = false
            }

            override fun onFailure(reason: Int) {}
        })
    }

    fun stop() {
        disconnect()
        runCatching { serverSocket?.close() }
        serverJob?.cancel()
        scope.cancel()
        runCatching { context.unregisterReceiver(p2pReceiver) }
    }

    private fun hasWifiPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
