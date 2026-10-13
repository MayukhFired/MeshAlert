package com.meshalert.app.gateway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.meshalert.app.R
import com.meshalert.app.db.MeshDatabase
import com.meshalert.app.model.MeshMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

/**
 * Store-and-Forward Delay Tolerant Gateway (DTN):
 * When any device in the offline emergency mesh escapes the blackout zone and catches
 * cellular reception or active internet, it automatically bridges accumulated offline SOS beacons
 * directly to emergency numbers via SMS!
 */
class SmsGatewayManager(private val context: Context) {

    companion object {
        private const val TAG = "SmsGatewayManager"
        private const val PREFS_KEY_PHONE = "emergency_contact_phone"
        private const val PREFS_KEY_AUTO_FORWARD = "auto_forward_sms_enabled"
        const val GATEWAY_NOTIF_ID = 3001
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val dao = MeshDatabase.getInstance(context).messageDao()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = context.getSharedPreferences("mesh_node", Context.MODE_PRIVATE)

    private val _hasConnectivity = MutableStateFlow(false)
    val hasConnectivity: StateFlow<Boolean> = _hasConnectivity.asStateFlow()

    private val _emergencyContactPhone = MutableStateFlow(
        prefs.getString(PREFS_KEY_PHONE, "") ?: ""
    )
    val emergencyContactPhone: StateFlow<String> = _emergencyContactPhone.asStateFlow()

    private val _isAutoForwardEnabled = MutableStateFlow(
        prefs.getBoolean(PREFS_KEY_AUTO_FORWARD, true)
    )
    val isAutoForwardEnabled: StateFlow<Boolean> = _isAutoForwardEnabled.asStateFlow()

    private val _forwardedCount = MutableStateFlow(0)
    val forwardedCount: StateFlow<Int> = _forwardedCount.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "📡 Internet/Cellular signal detected! Testing gateway relay...")
            _hasConnectivity.value = true
            scope.launch { checkAndForwardPendingSos() }
        }

        override fun onLost(network: Network) {
            Log.w(TAG, "Internet/Cellular signal lost. Returned to offline mesh mode.")
            _hasConnectivity.value = false
        }
    }

    fun start() {
        checkCurrentConnectivity()
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            connectivityManager?.registerNetworkCallback(request, networkCallback)
        }.onFailure {
            Log.e(TAG, "Failed to register network callback", it)
        }
    }

    fun stop() {
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        scope.cancel()
    }

    fun setEmergencyContactPhone(phone: String) {
        val trimmed = phone.trim()
        prefs.edit().putString(PREFS_KEY_PHONE, trimmed).apply()
        _emergencyContactPhone.value = trimmed
    }

    fun setAutoForwardEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREFS_KEY_AUTO_FORWARD, enabled).apply()
        _isAutoForwardEnabled.value = enabled
    }

    private fun checkCurrentConnectivity() {
        val activeNetwork = connectivityManager?.activeNetwork
        val caps = connectivityManager?.getNetworkCapabilities(activeNetwork)
        val hasNet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        _hasConnectivity.value = hasNet
    }

    /**
     * Checks if any SOS beacons need forwarding out to cellular SMS.
     */
    suspend fun checkAndForwardPendingSos(): Int = withContext(Dispatchers.IO) {
        if (!_isAutoForwardEnabled.value) return@withContext 0

        val phone = _emergencyContactPhone.value
        if (phone.isBlank()) {
            Log.d(TAG, "Skipping SMS gateway forward: No emergency contact phone number configured")
            return@withContext 0
        }

        if (!hasSmsPermission()) {
            Log.w(TAG, "Skipping SMS gateway forward: SEND_SMS permission not granted")
            return@withContext 0
        }

        val unforwarded = dao.getUnforwardedSosMessages()
        if (unforwarded.isEmpty()) return@withContext 0

        Log.i(TAG, "Found ${unforwarded.size} unforwarded SOS beacons. Forwarding via SMS to $phone...")

        var successCount = 0
        for (msg in unforwarded) {
            val sent = sendSosViaSms(msg, phone)
            if (sent) {
                dao.markForwarded(msg.id)
                successCount++
                _forwardedCount.value += 1
            }
        }

        if (successCount > 0) {
            postGatewayNotification(successCount, phone)
        }

        return@withContext successCount
    }

    private fun sendSosViaSms(msg: MeshMessage, targetPhone: String): Boolean {
        return runCatching {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
            val mapsLink = if (msg.latitude != 0.0 || msg.longitude != 0.0) {
                "https://maps.google.com/?q=%.4f,%.4f".format(msg.latitude, msg.longitude)
            } else {
                "No GPS fix"
            }

            val smsBody = """
                🚨 [MeshAlert SOS RELAY]
                Victim: ${msg.senderName}
                Loc: $mapsLink
                Info: ${msg.content}
                Hops: ${msg.hopCount} · Time: $timeStr
            """.trimIndent()

            val parts = smsManager.divideMessage(smsBody)
            smsManager.sendMultipartTextMessage(targetPhone, null, parts, null, null)
            Log.i(TAG, "Successfully forwarded SOS beacon (${msg.id}) via SMS to $targetPhone")
            true
        }.getOrElse {
            Log.e(TAG, "Failed sending SMS for SOS beacon ${msg.id}", it)
            false
        }
    }

    private fun postGatewayNotification(count: Int, phone: String) {
        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            ?: return

        val notif = NotificationCompat.Builder(context, "mesh_service")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("📡 DTN Mesh Gateway Forwarded")
            .setContentText("Sent $count offline SOS beacon(s) via SMS to $phone")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notifManager.notify(GATEWAY_NOTIF_ID, notif)
    }

    fun hasSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
