package com.meshalert.app.mesh

import android.content.Context
import com.meshalert.app.db.MeshDatabase
import com.meshalert.app.model.MeshMessage
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class MeshEngine(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dao = MeshDatabase.getInstance(context).messageDao()
    private val seenIds = mutableSetOf<String>()    // in-memory dedup cache

    private val bleManager = BleManager(context) { peerAddress ->
        // BLE found a peer — trigger WiFi Direct discovery
        wifiDirectManager.discoverPeers()
    }

    private val wifiDirectManager = WifiDirectManager(context) { raw ->
        scope.launch { handleIncoming(raw) }
    }

    fun start() {
        bleManager.startAdvertise()
        bleManager.startScan()
        scope.launch { wifiDirectManager.startServer() }
        scope.launch { purgeOldMessages() }
    }

    fun stop() {
        bleManager.stop()
        wifiDirectManager.disconnect()
        scope.cancel()
    }

    suspend fun broadcast(message: MeshMessage, peerHost: String? = null) {
        if (!shouldRelay(message)) return
        markSeen(message.id)
        dao.insert(message)
        val payload = encodeMessage(message)
        peerHost?.let { wifiDirectManager.sendMessage(it, payload) }
    }

    private suspend fun handleIncoming(raw: String) {
        val message = runCatching { decodeMessage(raw) }.getOrNull() ?: return
        if (!shouldRelay(message)) return
        markSeen(message.id)
        dao.insert(message)
        // Relay to other peers (flood with TTL decrement)
        val relayed = message.copy(ttl = message.ttl - 1, hopCount = message.hopCount + 1)
        if (relayed.ttl > 0) {
            // TODO: iterate known peer hosts and forward
        }
    }

    private fun shouldRelay(message: MeshMessage): Boolean =
        message.ttl > 0 && message.id !in seenIds

    private fun markSeen(id: String) {
        seenIds.add(id)
        if (seenIds.size > 500) seenIds.clear() // simple overflow guard
    }

    private suspend fun purgeOldMessages() {
        while (true) {
            delay(3_600_000L) // every hour
            val cutoff = System.currentTimeMillis() - 24 * 3_600_000L
            dao.deleteOlderThan(cutoff)
        }
    }

    private fun encodeMessage(msg: MeshMessage): String = Json.encodeToString(msg)
    private fun decodeMessage(raw: String): MeshMessage = Json.decodeFromString(raw)
}
