package com.meshalert.app.mesh

import android.content.Context
import android.net.wifi.p2p.*
import android.net.wifi.p2p.WifiP2pManager.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.ServerSocket
import java.net.Socket

class WifiDirectManager(
    private val context: Context,
    private val onMessageReceived: (String) -> Unit
) {
    companion object { const val PORT = 8988 }

    private val manager: WifiP2pManager =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel: Channel = manager.initialize(context, context.mainLooper, null)

    fun discoverPeers() {
        manager.discoverPeers(channel, object : ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {}
        })
    }

    fun connect(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        manager.connect(channel, config, object : ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {}
        })
    }

    suspend fun sendMessage(hostAddress: String, message: String) = withContext(Dispatchers.IO) {
        runCatching {
            Socket(hostAddress, PORT).use { socket ->
                socket.getOutputStream().bufferedWriter().use { it.write(message); it.flush() }
            }
        }
    }

    suspend fun startServer() = withContext(Dispatchers.IO) {
        runCatching {
            ServerSocket(PORT).use { server ->
                while (true) {
                    val client = server.accept()
                    val message = client.getInputStream().bufferedReader().readText()
                    client.close()
                    if (message.isNotBlank()) onMessageReceived(message)
                }
            }
        }
    }

    fun disconnect() {
        manager.removeGroup(channel, object : ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {}
        })
    }
}
