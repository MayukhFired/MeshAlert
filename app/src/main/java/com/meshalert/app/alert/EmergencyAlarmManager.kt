package com.meshalert.app.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.meshalert.app.MainActivity
import com.meshalert.app.R
import com.meshalert.app.model.MeshMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Handles loud audible acoustic alarms, Morse code haptic feedback,
 * and high-priority heads-up system notifications when emergency SOS signals arrive.
 */
class EmergencyAlarmManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "EmergencyAlarmManager"
        const val CHANNEL_SOS_ID = "mesh_critical_sos_channel"
        const val SOS_NOTIFICATION_BASE_ID = 2000

        // SOS in Morse code: ... (3 dots), --- (3 dashes), ... (3 dots) in milliseconds
        // [delay, dot, gap, dot, gap, dot, letter_gap, dash, gap, dash, gap, dash, letter_gap, dot, gap, dot, gap, dot, word_gap]
        val SOS_VIBRATION_PATTERN = longArrayOf(
            0,
            200, 100, 200, 100, 200,       // S (...)
            300,
            600, 100, 600, 100, 600,       // O (---)
            300,
            200, 100, 200, 100, 200,       // S (...)
            1200                           // Pause before loop
        )

        @Volatile
        private var INSTANCE: EmergencyAlarmManager? = null

        fun getInstance(context: Context): EmergencyAlarmManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: EmergencyAlarmManager(context.applicationContext).also { INSTANCE = it }
            }
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var alarmJob: Job? = null

    private val _isAlarmActive = MutableStateFlow(false)
    val isAlarmActive: StateFlow<Boolean> = _isAlarmActive.asStateFlow()

    private val _activeSosMessage = MutableStateFlow<MeshMessage?>(null)
    val activeSosMessage: StateFlow<MeshMessage?> = _activeSosMessage.asStateFlow()

    init {
        createSosNotificationChannel()
    }

    private fun createSosNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

            val channel = NotificationChannel(
                CHANNEL_SOS_ID,
                "Critical SOS Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Immediate audio & haptic alarms for received SOS beacons"
                enableVibration(true)
                vibrationPattern = SOS_VIBRATION_PATTERN
                setBypassDnd(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Trigger high-priority acoustic, haptic, and visual notification for an incoming SOS.
     */
    fun triggerSosAlarm(message: MeshMessage) {
        Log.w(TAG, "🚨 TRIGGERING EMERGENCY SOS ALARM: ${message.id} from ${message.senderName}")
        _activeSosMessage.value = message
        _isAlarmActive.value = true

        // 1. Post Heads-up Notification
        postSosNotification(message)

        // 2. Start repeating audible tone & haptic pulsing in background coroutine
        alarmJob?.cancel()
        alarmJob = scope.launch {
            startAcousticAndHapticLoop()
        }
    }

    /**
     * Silences any ongoing siren and haptic pattern.
     */
    fun silenceAlarm() {
        Log.i(TAG, "Silencing SOS alarm")
        alarmJob?.cancel()
        alarmJob = null
        _isAlarmActive.value = false
        _activeSosMessage.value = null
        vibrator?.cancel()
    }

    private suspend fun startAcousticAndHapticLoop() {
        var toneGen: ToneGenerator? = null
        try {
            toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ToneGenerator", e)
        }

        while (_isAlarmActive.value) {
            // Trigger Morse Vibration
            startVibration()

            // Play alternating high-frequency emergency beeps
            repeat(3) {
                toneGen?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 300)
                delay(350)
            }
            delay(200)
            repeat(3) {
                toneGen?.startTone(ToneGenerator.TONE_SUP_ERROR, 500)
                delay(550)
            }
            delay(200)
            repeat(3) {
                toneGen?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 300)
                delay(350)
            }

            // Pause before repeating next SOS sequence
            delay(2000)
        }

        toneGen?.release()
    }

    private fun startVibration() {
        vibrator?.let { v ->
            if (v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = VibrationEffect.createWaveform(SOS_VIBRATION_PATTERN, -1)
                    v.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(SOS_VIBRATION_PATTERN, -1)
                }
            }
        }
    }

    private fun postSosNotification(message: MeshMessage) {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingTap = PendingIntent.getActivity(
            context,
            message.id.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val silenceIntent = Intent(context, MainActivity::class.java).apply {
            action = "ACTION_SILENCE_SOS"
        }
        val pendingSilence = PendingIntent.getActivity(
            context,
            message.id.hashCode() + 1,
            silenceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_SOS_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("🚨 EMERGENCY SOS DETECTED!")
            .setContentText("Beacon received from ${message.senderName} (${message.hopCount} hops away)")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("🚨 CRITICAL SOS ALERT\nFrom: ${message.senderName}\n${message.content}\n\nHops: ${message.hopCount} · TTL: ${message.ttl}")
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingTap)
            .addAction(0, "SILENCE ALARM", pendingSilence)
            .build()

        notificationManager.notify(SOS_NOTIFICATION_BASE_ID, notif)
    }
}
