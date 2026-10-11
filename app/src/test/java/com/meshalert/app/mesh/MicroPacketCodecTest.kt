package com.meshalert.app.mesh

import com.meshalert.app.model.AlertType
import org.junit.Assert.*
import org.junit.Test

class MicroPacketCodecTest {

    @Test
    fun testEncodeAndDecodeSosPacket() {
        val original = MicroPacket(
            version = 1,
            type = AlertType.SOS,
            latitude = 37.774929,
            longitude = -122.419416,
            batteryPercent = 85,
            survivorCount = 3,
            timestampSeconds = 1728300000L,
            ttl = 8,
            hopCount = 2,
            senderHash = 0x123456789ABCDEF0L
        )

        val encoded = MicroPacketCodec.encode(original)
        assertEquals(MicroPacketCodec.PACKET_SIZE, encoded.size)

        val decoded = MicroPacketCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(original.version, decoded!!.version)
        assertEquals(original.type, decoded.type)
        assertEquals(original.latitude, decoded.latitude, 0.00001)
        assertEquals(original.longitude, decoded.longitude, 0.00001)
        assertEquals(original.batteryPercent, decoded.batteryPercent)
        assertEquals(original.survivorCount, decoded.survivorCount)
        assertEquals(original.timestampSeconds, decoded.timestampSeconds)
        assertEquals(original.ttl, decoded.ttl)
        assertEquals(original.hopCount, decoded.hopCount)
        assertEquals(original.senderHash, decoded.senderHash)
    }

    @Test
    fun testChecksumDetectsCorruption() {
        val original = MicroPacket(
            version = 1,
            type = AlertType.SHELTER,
            latitude = -33.868820,
            longitude = 151.209296,
            batteryPercent = 42,
            survivorCount = 10,
            timestampSeconds = 1728312345L,
            ttl = 5,
            hopCount = 1,
            senderHash = 0xAABBCCDDEEFF0011L
        )

        val encoded = MicroPacketCodec.encode(original)

        // Corrupt a byte in payload (simulate radio bit-flip)
        encoded[5] = (encoded[5].toInt() xor 0xFF).toByte()

        val decoded = MicroPacketCodec.decode(encoded)
        assertNull("Corrupted packet must fail CRC-8 check and return null", decoded)
    }

    @Test
    fun testBoundaryCoordinates() {
        val packetNorthPole = MicroPacket(
            version = 1,
            type = AlertType.ROAD_BLOCKED,
            latitude = 90.0,
            longitude = -180.0,
            batteryPercent = 100,
            survivorCount = 1,
            timestampSeconds = 1000L,
            ttl = 15,
            hopCount = 0,
            senderHash = 1L
        )

        val encoded = MicroPacketCodec.encode(packetNorthPole)
        val decoded = MicroPacketCodec.decode(encoded)

        assertNotNull(decoded)
        assertEquals(90.0, decoded!!.latitude, 0.00001)
        assertEquals(-180.0, decoded.longitude, 0.00001)
    }
}
