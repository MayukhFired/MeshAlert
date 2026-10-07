package com.meshalert.app.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshalert.app.db.MeshDatabase
import com.meshalert.app.model.AlertType
import com.meshalert.app.model.MeshMessage
import com.meshalert.app.service.MeshForegroundService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = MeshDatabase.getInstance(app).messageDao()

    val messages = dao.getAllMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun startService() {
        val ctx = getApplication<Application>()
        ctx.startForegroundService(Intent(ctx, MeshForegroundService::class.java))
    }

    fun stopService() {
        val ctx = getApplication<Application>()
        ctx.startService(
            Intent(ctx, MeshForegroundService::class.java)
                .apply { action = MeshForegroundService.ACTION_STOP }
        )
    }

    fun sendSos(name: String, lat: Double, lon: Double) {
        viewModelScope.launch {
            val msg = MeshMessage(
                senderId = UUID.randomUUID().toString(),
                senderName = name,
                type = AlertType.SOS,
                content = "🆘 SOS from $name",
                latitude = lat,
                longitude = lon
            )
            dao.insert(msg)
            // MeshEngine will pick this up and relay
        }
    }

    fun sendAlert(name: String, type: AlertType, content: String) {
        viewModelScope.launch {
            dao.insert(
                MeshMessage(
                    senderId = UUID.randomUUID().toString(),
                    senderName = name,
                    type = type,
                    content = content
                )
            )
        }
    }
}
