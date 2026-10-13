package com.meshalert.app.mesh

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import com.meshalert.app.alert.EmergencyAlarmManager
import com.meshalert.app.db.MeshDatabase
import com.meshalert.app.model.AlertType
import com.meshalert.app.model.MeshMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class MeshEngine private constructor(private val context: Context) {

    companion object {
        private const val TAG = "MeshEngine"

        @Volatile
        private var INSTANCE: MeshEngine? = null

        fun getInstance(context: Context): MeshEngine =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MeshEngine(context.applicationContext).also { INSTANCE = it }
            }
    }

    val alarmManager = EmergencyAlarmManager.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dao = MeshDatabase.getInstance(context).messageDao()
    private val seenIds = LinkedHashSet<String>() // ordered dedup cache for FIFO eviction


    private val bleManager = BleManager(
        context = context,
        onDeviceFound = { peerAddress ->
            Log.d(TAG, "BLE peer detected ($peerAddress) -> triggering WiFi Direct peer discovery")
            wifiDirectManager.discoverPeers()
        },
        onPacketReceived = { rawBytes, peerAddress ->
            scope.launch { handleIncomingMicroPacket(rawBytes, peerAddress) }
        }
    )

    private val wifiDirectManager = WifiDirectManager(context) { raw ->
        scope.launch { handleIncomingWifiDirect(raw) }
    }

    fun start() {
        Log.i(TAG, "Starting MeshEngine radio services...")
        bleManager.startAdvertise()
        bleManager.startScan()
        scope.launch { wifiDirectManager.startServer() }
        scope.launch { purgeOldMessages() }
    }

    fun stop() {
        Log.i(TAG, "Stopping MeshEngine...")
        alarmManager.silenceAlarm()
        bleManager.stop()
        wifiDirectManager.disconnect()
        scope.cancel()
    }

    fun getAllMessages(): Flow<List<MeshMessage>> = dao.getAllMessages()

    suspend fun broadcast(
        message: MeshMessage,
        survivorCount: Int = 1,
        peerHost: String? = null
    ) {
        if (!shouldRelay(message.id, message.ttl)) return
        markSeen(message.id)
        dao.insert(message)

        // 1. Convert to compact 25-byte MicroPacket for zero-pairing BLE advertisement
        val battery = getBatteryPercentage()
        val microPacket = MicroPacketCodec.fromMeshMessage(
            msg = message,
            batteryPercent = battery,
            survivorCount = survivorCount
        )
        val packetBytes = MicroPacketCodec.encode(microPacket)

        Log.d(TAG, "Broadcasting SOS/Alert packet via BLE (ID: ${message.id}, Batt: $battery%)")
        bleManager.broadcastPacket(packetBytes)

        // 2. Transmit via WiFi Direct if a peer socket is active
        peerHost?.let {
            val payload = encodeMessage(message)
            wifiDirectManager.sendMessage(it, payload)
        }
    }

    private suspend fun handleIncomingMicroPacket(rawBytes: ByteArray, peerAddress: String) {
        val packet = MicroPacketCodec.decode(rawBytes) ?: run {
            Log.w(TAG, "Discarded packet from $peerAddress: CRC mismatch or corrupt payload")
            return
        }

        val message = MicroPacketCodec.toMeshMessage(packet)
        Log.i(TAG, "Decoded incoming MicroPacket: ${message.type} from ${message.senderName} (TTL: ${packet.ttl}, Hops: ${packet.hopCount})")

        if (!shouldRelay(message.id, packet.ttl)) {
            Log.d(TAG, "Ignoring duplicate or expired packet: ${message.id}")
            return
        }

        markSeen(message.id)
        dao.insert(message)

        // Trigger alarm immediately if incoming packet is an emergency SOS from a peer
        if (message.type == AlertType.SOS && !isLocalDevice(packet.senderHash)) {
            alarmManager.triggerSosAlarm(message)
        }

        // Multi-hop flooding: Decrement TTL, increment Hop Count and re-broadcast over BLE
        if (packet.ttl > 1) {
            val relayedPacket = packet.copy(
                ttl = packet.ttl - 1,
                hopCount = packet.hopCount + 1
            )
            val relayedBytes = MicroPacketCodec.encode(relayedPacket)
            Log.i(TAG, "Relaying packet over mesh: ${message.id} -> New TTL: ${relayedPacket.ttl}, Hops: ${relayedPacket.hopCount}")
            bleManager.broadcastPacket(relayedBytes)
        }
    }

    private suspend fun handleIncomingWifiDirect(raw: String) {
        val message = runCatching { decodeMessage(raw) }.getOrNull() ?: return
        if (!shouldRelay(message.id, message.ttl)) return
        markSeen(message.id)
        dao.insert(message)

        if (message.type == AlertType.SOS) {
            alarmManager.triggerSosAlarm(message)
        }

        val relayed = message.copy(ttl = message.ttl - 1, hopCount = message.hopCount + 1)
        if (relayed.ttl > 0) {
            val microPacket = MicroPacketCodec.fromMeshMessage(relayed, getBatteryPercentage())
            bleManager.broadcastPacket(MicroPacketCodec.encode(microPacket))
        }
    }

    private fun isLocalDevice(senderHash: Long): Boolean {
        return runCatching {
            val prefs = context.getSharedPreferences("mesh_node", Context.MODE_PRIVATE)
            val localId = prefs.getString("node_id", null) ?: return false
            val name = prefs.getString("user_name", "Survivor") ?: "Survivor"
            val localHash = localId.hashCode().toLong().shl(32) or (name.hashCode().toLong() and 0xFFFFFFFFL)
            senderHash == localHash
        }.getOrDefault(false)
    }


    private fun shouldRelay(id: String, ttl: Int): Boolean {
        synchronized(seenIds) {
            return ttl > 0 && id !in seenIds
        }
    }

    private fun markSeen(id: String) {
        synchronized(seenIds) {
            seenIds.add(id)
            if (seenIds.size > 1000) {
                // Remove oldest 200 items in FIFO order
                val iterator = seenIds.iterator()
                var removed = 0
                while (iterator.hasNext() && removed < 200) {
                    iterator.next()
                    iterator.remove()
                    removed++
                }
            }
        }
    }

    private fun getBatteryPercentage(): Int {
        return runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            if (level in 0..100) level else 100
        }.getOrDefault(100)
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
