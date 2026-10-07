package com.meshalert.app.mesh

import com.meshalert.app.model.AlertType
import com.meshalert.app.model.MeshMessage
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 25-Byte MicroPacket Data Structure:
 *
 * Byte  0:      [Version: 4 bits][AlertType Ordinal: 4 bits]
 * Bytes 1..4:   Latitude (32-bit signed int, micro-degrees, factor 1,000,000)
 * Bytes 5..8:   Longitude (32-bit signed int, micro-degrees, factor 1,000,000)
 * Byte  9:      Battery Percentage (0..100)
 * Byte  10:     Survivor Count (0..255)
 * Bytes 11..14: Timestamp in Unix epoch seconds (32-bit uint)
 * Byte  15:     [TTL: 4 bits (0..15)][HopCount: 4 bits (0..15)]
 * Bytes 16..23: Sender Hash / Device Node ID (64-bit Long)
 * Byte  24:     CRC-8 Checksum over bytes 0..23
 *
 * Total size = 25 bytes.
 * Fits within the 31-byte legacy BLE advertisement payload without pairing!
 */
data class MicroPacket(
    val version: Int = 1,
    val type: AlertType,
    val latitude: Double,
    val longitude: Double,
    val batteryPercent: Int,
    val survivorCount: Int,
    val timestampSeconds: Long,
    val ttl: Int = 10,
    val hopCount: Int = 0,
    val senderHash: Long
)

object MicroPacketCodec {
    const val PACKET_SIZE = 25
    private const val CURRENT_VERSION = 1
    private const val DEGREE_SCALE = 1_000_000.0

    fun encode(packet: MicroPacket): ByteArray {
        val buffer = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.BIG_ENDIAN)

        // Byte 0: Version (4 bits) + Type (4 bits)
        val header = ((packet.version and 0x0F) shl 4) or (packet.type.ordinal and 0x0F)
        buffer.put(header.toByte())

        // Bytes 1..4: Lat (micro-degrees)
        val latInt = (packet.latitude * DEGREE_SCALE).toInt()
        buffer.putInt(latInt)

        // Bytes 5..8: Lon (micro-degrees)
        val lonInt = (packet.longitude * DEGREE_SCALE).toInt()
        buffer.putInt(lonInt)

        // Byte 9: Battery % (0..100)
        buffer.put(packet.batteryPercent.coerceIn(0, 100).toByte())

        // Byte 10: Survivor Count
        buffer.put(packet.survivorCount.coerceIn(0, 255).toByte())

        // Bytes 11..14: Timestamp seconds
        buffer.putInt((packet.timestampSeconds and 0xFFFFFFFFL).toInt())

        // Byte 15: TTL (4 bits) + HopCount (4 bits)
        val hops = ((packet.ttl.coerceIn(0, 15) and 0x0F) shl 4) or (packet.hopCount.coerceIn(0, 15) and 0x0F)
        buffer.put(hops.toByte())

        // Bytes 16..23: Sender Hash (Long)
        buffer.putLong(packet.senderHash)

        // Byte 24: CRC-8
        val data = buffer.array()
        val crc = calculateCrc8(data, 0, PACKET_SIZE - 1)
        data[PACKET_SIZE - 1] = crc

        return data
    }

    fun decode(bytes: ByteArray): MicroPacket? {
        if (bytes.size < PACKET_SIZE) return null

        // Checksum verification
        val expectedCrc = calculateCrc8(bytes, 0, PACKET_SIZE - 1)
        val actualCrc = bytes[PACKET_SIZE - 1]
        if (expectedCrc != actualCrc) return null

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        // Byte 0
        val header = buffer.get().toInt() and 0xFF
        val version = (header ushr 4) and 0x0F
        val typeOrdinal = header and 0x0F
        val alertTypes = AlertType.entries
        val type = if (typeOrdinal < alertTypes.size) alertTypes[typeOrdinal] else AlertType.SOS

        // Lat / Lon
        val latInt = buffer.getInt()
        val lonInt = buffer.getInt()
        val latitude = latInt / DEGREE_SCALE
        val longitude = lonInt / DEGREE_SCALE

        // Battery & Survivors
        val battery = buffer.get().toInt() and 0xFF
        val survivors = buffer.get().toInt() and 0xFF

        // Timestamp seconds (unsigned 32-bit)
        val timestampSeconds = buffer.getInt().toLong() and 0xFFFFFFFFL

        // TTL & Hops
        val hops = buffer.get().toInt() and 0xFF
        val ttl = (hops ushr 4) and 0x0F
        val hopCount = hops and 0x0F

        // Sender Hash
        val senderHash = buffer.getLong()

        return MicroPacket(
            version = version,
            type = type,
            latitude = latitude,
            longitude = longitude,
            batteryPercent = battery,
            survivorCount = survivors,
            timestampSeconds = timestampSeconds,
            ttl = ttl,
            hopCount = hopCount,
            senderHash = senderHash
        )
    }

    fun calculateCrc8(data: ByteArray, offset: Int, length: Int): Byte {
        var crc = 0x00
        for (i in offset until (offset + length)) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (bit in 0 until 8) {
                crc = if ((crc and 0x80) != 0) {
                    ((crc shl 1) xor 0x07) and 0xFF
                } else {
                    (crc shl 1) and 0xFF
                }
            }
        }
        return (crc and 0xFF).toByte()
    }

    fun fromMeshMessage(msg: MeshMessage, batteryPercent: Int = 100, survivorCount: Int = 1): MicroPacket {
        val senderHash = msg.senderId.hashCode().toLong().shl(32) or (msg.senderName.hashCode().toLong() and 0xFFFFFFFFL)
        return MicroPacket(
            version = CURRENT_VERSION,
            type = msg.type,
            latitude = msg.latitude,
            longitude = msg.longitude,
            batteryPercent = batteryPercent,
            survivorCount = survivorCount,
            timestampSeconds = msg.timestamp / 1000L,
            ttl = msg.ttl,
            hopCount = msg.hopCount,
            senderHash = senderHash
        )
    }

    fun toMeshMessage(packet: MicroPacket): MeshMessage {
        val hexSender = "%016X".format(packet.senderHash)
        return MeshMessage(
            id = "micro-$hexSender-${packet.timestampSeconds}",
            senderId = hexSender,
            senderName = "Node-${hexSender.take(4)}",
            type = packet.type,
            content = when (packet.type) {
                AlertType.SOS -> "🆘 SOS BEACON (Survivors: ${packet.survivorCount}, Batt: ${packet.batteryPercent}%)"
                AlertType.SHELTER -> "⛺ Shelter Location (Batt: ${packet.batteryPercent}%)"
                AlertType.ROAD_BLOCKED -> "⚠️ Road Hazard / Blockage"
                AlertType.INFO -> "ℹ️ Emergency Info Beacon"
                AlertType.CUSTOM -> "📍 Peer Broadcast"
            },
            latitude = packet.latitude,
            longitude = packet.longitude,
            timestamp = packet.timestampSeconds * 1000L,
            ttl = packet.ttl,
            hopCount = packet.hopCount
        )
    }
}
