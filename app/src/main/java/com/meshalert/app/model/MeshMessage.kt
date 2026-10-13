package com.meshalert.app.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.UUID

enum class AlertType { SOS, INFO, ROAD_BLOCKED, SHELTER, CUSTOM }

@Serializable
@Entity(tableName = "messages")
data class MeshMessage(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val senderName: String,
    val type: AlertType,
    val content: String,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis(),
    val ttl: Int = 10,           // hops remaining
    val hopCount: Int = 0,       // hops travelled
    val isForwarded: Boolean = false
)

