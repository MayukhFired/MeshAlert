package com.meshalert.app.alert

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sin

/**
 * Manages physical hardware emergency beacons:
 * 1. Camera Flashlight SOS Strobe (International Morse: ... --- ...)
 * 2. Acoustic Rescue Whistle (3.0 kHz high-penetration synthesized pulse)
 */
class EmergencyToolsManager(private val context: Context) {

    companion object {
        private const val TAG = "EmergencyToolsManager"
        private const val SAMPLE_RATE = 44100
        private const val WHISTLE_FREQ_HZ = 3000.0 // 3 kHz reaches furthest through disaster debris
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var strobeJob: Job? = null
    private var whistleJob: Job? = null

    private val _isStrobeActive = MutableStateFlow(false)
    val isStrobeActive: StateFlow<Boolean> = _isStrobeActive.asStateFlow()

    private val _isWhistleActive = MutableStateFlow(false)
    val isWhistleActive: StateFlow<Boolean> = _isWhistleActive.asStateFlow()

    private val cameraIdWithFlash: String? by lazy {
        findCameraWithFlash()
    }

    private fun findCameraWithFlash(): String? {
        val cm = cameraManager ?: return null
        return runCatching {
            cm.cameraIdList.firstOrNull { id ->
                val characteristics = cm.getCameraCharacteristics(id)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
    }

    fun toggleSosStrobe() {
        if (_isStrobeActive.value) {
            stopStrobe()
        } else {
            startSosStrobe()
        }
    }

    fun startSosStrobe() {
        val camId = cameraIdWithFlash
        if (camId == null) {
            Log.w(TAG, "No camera with flash unit available on this device")
            return
        }

        stopStrobe()
        _isStrobeActive.value = true

        strobeJob = scope.launch {
            Log.i(TAG, "Starting SOS optical strobe pattern")
            try {
                while (isActive && _isStrobeActive.value) {
                    // S: 3 short flashes (150ms ON, 150ms OFF)
                    repeat(3) {
                        setTorch(camId, true)
                        delay(150)
                        setTorch(camId, false)
                        delay(150)
                    }

                    delay(300) // letter gap

                    // O: 3 long flashes (450ms ON, 150ms OFF)
                    repeat(3) {
                        setTorch(camId, true)
                        delay(450)
                        setTorch(camId, false)
                        delay(150)
                    }

                    delay(300) // letter gap

                    // S: 3 short flashes (150ms ON, 150ms OFF)
                    repeat(3) {
                        setTorch(camId, true)
                        delay(150)
                        setTorch(camId, false)
                        delay(150)
                    }

                    // Word pause before repeat
                    delay(1200)
                }
            } finally {
                setTorch(camId, false)
            }
        }
    }

    fun stopStrobe() {
        strobeJob?.cancel()
        strobeJob = null
        _isStrobeActive.value = false
        cameraIdWithFlash?.let { setTorch(it, false) }
    }

    private fun setTorch(camId: String, enabled: Boolean) {
        runCatching {
            cameraManager?.setTorchMode(camId, enabled)
        }.onFailure {
            Log.e(TAG, "Failed to set torch mode to $enabled", it)
        }
    }

    fun toggleWhistle() {
        if (_isWhistleActive.value) {
            stopWhistle()
        } else {
            startWhistle()
        }
    }

    fun startWhistle() {
        stopWhistle()
        _isWhistleActive.value = true

        whistleJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "Starting acoustic rescue whistle synthesis")
            var audioTrack: AudioTrack? = null
            try {
                // Generate 0.5s tone buffer of 3.0 kHz sine wave
                val durationMs = 500
                val numSamples = (SAMPLE_RATE * (durationMs / 1000.0)).toInt()
                val buffer = ShortArray(numSamples)
                for (i in 0 until numSamples) {
                    val angle = 2.0 * Math.PI * i / (SAMPLE_RATE / WHISTLE_FREQ_HZ)
                    // Synthesize loud sine wave with slight soft ramp at edges
                    val envelope = when {
                        i < 400 -> i / 400.0
                        i > numSamples - 400 -> (numSamples - i) / 400.0
                        else -> 1.0
                    }
                    buffer[i] = (sin(angle) * Short.MAX_VALUE * 0.95 * envelope).toInt().toShort()
                }

                val minBufferSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBufferSize, buffer.size * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack.play()

                while (isActive && _isWhistleActive.value) {
                    // 3 loud whistle bursts in succession
                    repeat(3) {
                        if (!isActive || !_isWhistleActive.value) return@repeat
                        audioTrack.write(buffer, 0, buffer.size)
                        delay(600) // length of burst + short pause
                    }
                    delay(1200) // pause between whistle groups
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in acoustic whistle generator", e)
            } finally {
                runCatching {
                    audioTrack?.stop()
                    audioTrack?.release()
                }
            }
        }
    }

    fun stopWhistle() {
        whistleJob?.cancel()
        whistleJob = null
        _isWhistleActive.value = false
    }

    fun stopAll() {
        stopStrobe()
        stopWhistle()
        scope.cancel()
    }
}
