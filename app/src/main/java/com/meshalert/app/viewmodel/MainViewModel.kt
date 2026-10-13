package com.meshalert.app.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshalert.app.alert.EmergencyToolsManager
import com.meshalert.app.location.LocationHelper
import com.meshalert.app.mesh.MeshEngine
import com.meshalert.app.model.AlertType
import com.meshalert.app.model.MeshMessage
import com.meshalert.app.sensor.CompassSensorManager
import com.meshalert.app.service.MeshForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

import android.net.wifi.p2p.WifiP2pDevice

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val meshEngine = MeshEngine.getInstance(app)
    val locationHelper = LocationHelper(app)
    val compassSensorManager = CompassSensorManager(app)
    val emergencyTools = EmergencyToolsManager(app)

    val currentLocation: StateFlow<Location?> = locationHelper.currentLocation
    val deviceHeading: StateFlow<Float> = compassSensorManager.azimuthDegrees

    val messages = meshEngine.getAllMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isAlarmActive: StateFlow<Boolean> = meshEngine.alarmManager.isAlarmActive
    val activeSosMessage: StateFlow<MeshMessage?> = meshEngine.alarmManager.activeSosMessage

    val isStrobeActive: StateFlow<Boolean> = emergencyTools.isStrobeActive
    val isWhistleActive: StateFlow<Boolean> = emergencyTools.isWhistleActive

    val isWifiDirectConnected: StateFlow<Boolean> = meshEngine.wifiDirect.isConnected
    val wifiDirectPeers: StateFlow<List<WifiP2pDevice>> = meshEngine.wifiDirect.peerList

    private val _selectedRadarTarget = MutableStateFlow<MeshMessage?>(null)

    val selectedRadarTarget: StateFlow<MeshMessage?> = _selectedRadarTarget.asStateFlow()

    fun selectRadarTarget(target: MeshMessage?) {
        _selectedRadarTarget.value = target
    }

    fun silenceAlarm() {
        meshEngine.alarmManager.silenceAlarm()
    }

    fun toggleSosStrobe() {
        emergencyTools.toggleSosStrobe()
    }

    fun toggleWhistle() {
        emergencyTools.toggleWhistle()
    }


    init {
        locationHelper.startListening()
        compassSensorManager.startListening()
    }


    fun startService() {
        val ctx = getApplication<Application>()
        ctx.startForegroundService(Intent(ctx, MeshForegroundService::class.java))
        locationHelper.startListening()
    }

    fun stopService() {
        val ctx = getApplication<Application>()
        ctx.startService(
            Intent(ctx, MeshForegroundService::class.java)
                .apply { action = MeshForegroundService.ACTION_STOP }
        )
    }

    fun sendSos(name: String = "Survivor", survivorCount: Int = 1) {
        viewModelScope.launch {
            val deviceId = getOrGenerateDeviceId()
            val loc = currentLocation.value
            val lat = loc?.latitude ?: 0.0
            val lon = loc?.longitude ?: 0.0

            val locInfo = if (loc != null) {
                " (GPS: %.4f, %.4f)".format(lat, lon)
            } else {
                " (No GPS fix)"
            }

            val msg = MeshMessage(
                senderId = deviceId,
                senderName = name,
                type = AlertType.SOS,
                content = "🆘 SOS from $name (Survivors: $survivorCount)$locInfo",
                latitude = lat,
                longitude = lon
            )
            meshEngine.broadcast(msg, survivorCount = survivorCount)
        }
    }

    fun sendAlert(name: String, type: AlertType, content: String) {
        viewModelScope.launch {
            val deviceId = getOrGenerateDeviceId()
            val loc = currentLocation.value
            val lat = loc?.latitude ?: 0.0
            val lon = loc?.longitude ?: 0.0

            val msg = MeshMessage(
                senderId = deviceId,
                senderName = name,
                type = type,
                content = content,
                latitude = lat,
                longitude = lon
            )
            meshEngine.broadcast(msg)
        }
    }

    private fun getOrGenerateDeviceId(): String {
        val prefs = getApplication<Application>().getSharedPreferences("mesh_node", Context.MODE_PRIVATE)
        var id = prefs.getString("node_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("node_id", id).apply()
        }
        return id
    }

    override fun onCleared() {
        super.onCleared()
        locationHelper.stopListening()
        compassSensorManager.stopListening()
        emergencyTools.stopAll()
    }


}
