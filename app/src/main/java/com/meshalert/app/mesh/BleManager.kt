package com.meshalert.app.mesh

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Robust BLE Radio Manager for MeshAlert:
 * - Manages BLE scanning and advertising with zero-pairing 25-byte MicroPackets.
 * - Prioritized round-robin advertisement queue prevents packet starvation.
 * - Emergency SOS packets preempt regular beacons and get higher air-time.
 * - Listens to Bluetooth hardware state changes and automatically resumes radio operations.
 */
class BleManager(
    private val context: Context,
    private val onDeviceFound: (String) -> Unit,
    private val onPacketReceived: (ByteArray, String) -> Unit
) {

    companion object {
        private const val TAG = "BleManager"
        // 16-bit UUID alias 0xFEED in standard Bluetooth Base UUID
        val SERVICE_UUID: UUID = UUID.fromString("0000FEED-0000-1000-8000-00805F9B34FB")
        val PARCEL_SERVICE_UUID = ParcelUuid(SERVICE_UUID)

        private const val SOS_BURST_DURATION_MS = 3500L      // 3.5s per SOS cycle
        private const val REGULAR_BURST_DURATION_MS = 2000L  // 2s per alert/hazard cycle
        private const val HEARTBEAT_INTERVAL_MS = 4000L      // 4s presence heartbeat
        private const val MAX_QUEUE_SIZE = 25
    }

    private data class QueuedPacket(
        val data: ByteArray,
        val isSos: Boolean,
        var repeatRemaining: Int,
        val burstDurationMs: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is QueuedPacket) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            return data.contentHashCode()
        }
    }

    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    private var isAdvertising = false
    private var isScanning = false
    private var isRunning = false

    // Queue & Concurrency Management
    private val queueScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val queueMutex = Mutex()
    private val packetQueue = mutableListOf<QueuedPacket>()
    private var queueWorkerJob: Job? = null

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Log.d(TAG, "BLE advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.e(TAG, "BLE advertising failed with errorCode: $errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val deviceAddress = runCatching { result.device.address }.getOrDefault("UNKNOWN")

            // Check if packet carries 25-byte MicroPacket service data
            val serviceData = record.getServiceData(PARCEL_SERVICE_UUID)
            if (serviceData != null && serviceData.size >= MicroPacketCodec.PACKET_SIZE) {
                Log.d(TAG, "MicroPacket received via BLE from $deviceAddress (${serviceData.size} bytes)")
                onPacketReceived(serviceData, deviceAddress)
                return
            }

            // Fallback: Check if device advertises our emergency service UUID
            val hasServiceUuid = record.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
            if (hasServiceUuid) {
                Log.d(TAG, "Peer presence beacon found at $deviceAddress")
                onDeviceFound(deviceAddress)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with errorCode: $errorCode")
            isScanning = false
        }
    }

    // Hardware State Broadcast Receiver
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.i(TAG, "Bluetooth turned ON: Auto-restarting BLE radio services")
                        if (isRunning) {
                            startScan()
                            startQueueWorker()
                        }
                    }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        Log.w(TAG, "Bluetooth turned OFF: Pausing BLE radio")
                        pauseRadio()
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(
            context,
            bluetoothStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun start() {
        isRunning = true
        startScan()
        startQueueWorker()
    }

    fun startScan() {
        if (!hasScanPermission()) {
            Log.w(TAG, "Scan permission missing, skipping startScan")
            return
        }
        if (adapter?.isEnabled != true) {
            Log.w(TAG, "Bluetooth disabled, cannot scan")
            return
        }

        scanner = adapter?.bluetoothLeScanner ?: return
        if (isScanning) return

        val filterServiceData = ScanFilter.Builder()
            .setServiceData(PARCEL_SERVICE_UUID, null)
            .build()
        val filterServiceUuid = ScanFilter.Builder()
            .setServiceUuid(PARCEL_SERVICE_UUID)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        runCatching {
            scanner?.startScan(listOf(filterServiceData, filterServiceUuid), settings, scanCallback)
            isScanning = true
            Log.d(TAG, "BLE scan started")
        }.onFailure {
            Log.e(TAG, "Failed to start BLE scan", it)
        }
    }

    fun stopScan() {
        if (!hasScanPermission()) return
        if (!isScanning) return
        runCatching {
            scanner?.stopScan(scanCallback)
            isScanning = false
            Log.d(TAG, "BLE scan stopped")
        }.onFailure {
            Log.e(TAG, "Failed to stop BLE scan", it)
        }
    }

    /**
     * Enqueues an outgoing packet into the priority round-robin broadcast queue.
     * SOS packets preempt regular beacons and get more airtime repetitions.
     */
    fun broadcastPacket(packetData: ByteArray, isSos: Boolean = false) {
        queueScope.launch {
            queueMutex.withLock {
                val existing = packetQueue.firstOrNull { it.data.contentEquals(packetData) }
                if (existing != null) {
                    // Boost repetition count if refreshed
                    existing.repeatRemaining = if (isSos) 8 else 4
                } else {
                    if (packetQueue.size >= MAX_QUEUE_SIZE) {
                        // Evict oldest non-SOS packet, or oldest packet if all are SOS
                        val indexToRemove = packetQueue.indexOfFirst { !it.isSos }.takeIf { it != -1 } ?: 0
                        packetQueue.removeAt(indexToRemove)
                    }

                    val item = QueuedPacket(
                        data = packetData,
                        isSos = isSos,
                        repeatRemaining = if (isSos) 8 else 4,
                        burstDurationMs = if (isSos) SOS_BURST_DURATION_MS else REGULAR_BURST_DURATION_MS
                    )

                    if (isSos) {
                        // Insert SOS at the front for immediate transmission
                        packetQueue.add(0, item)
                    } else {
                        packetQueue.add(item)
                    }
                }
            }

            // If queue worker is dormant or on heartbeat, prompt immediate re-evaluation
            if (isSos) {
                queueWorkerJob?.cancel()
                startQueueWorker()
            }
        }
    }

    private fun startQueueWorker() {
        if (queueWorkerJob?.isActive == true) return

        queueWorkerJob = queueScope.launch {
            while (isRunning && isActive) {
                var itemToAdvertise: QueuedPacket? = null

                queueMutex.withLock {
                    if (packetQueue.isNotEmpty()) {
                        // Find next item to transmit (SOS items get priority)
                        val sosIndex = packetQueue.indexOfFirst { it.isSos }
                        val targetIndex = if (sosIndex != -1) sosIndex else 0
                        itemToAdvertise = packetQueue[targetIndex]

                        // Decrement repeat counter
                        itemToAdvertise?.let { item ->
                            item.repeatRemaining--
                            if (item.repeatRemaining <= 0) {
                                packetQueue.removeAt(targetIndex)
                            }
                        }
                    }
                }

                if (itemToAdvertise != null) {
                    val item = itemToAdvertise!!
                    Log.d(TAG, "Queue transmitting packet (SOS=${item.isSos}, left=${item.repeatRemaining})")
                    startAdvertiseInternal(item.data)
                    delay(item.burstDurationMs)
                } else {
                    // Queue is empty: Transmit presence/heartbeat beacon
                    startAdvertiseInternal(null)
                    delay(HEARTBEAT_INTERVAL_MS)
                }
            }
        }
    }

    private fun startAdvertiseInternal(packetData: ByteArray? = null) {
        if (!hasAdvertisePermission()) {
            Log.w(TAG, "Advertise permission missing, skipping startAdvertise")
            return
        }
        if (adapter?.isEnabled != true) {
            Log.w(TAG, "Bluetooth disabled, cannot advertise")
            return
        }

        advertiser = adapter?.bluetoothLeAdvertiser ?: return

        // Stop any active advertising before restarting with new data
        if (isAdvertising) {
            runCatching { advertiser?.stopAdvertising(advertiseCallback) }
            isAdvertising = false
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0) // Run until explicitly stopped
            .build()

        val dataBuilder = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)

        if (packetData != null) {
            // Pack the 25-byte payload directly into 16-bit Service Data (Total: 29 bytes <= 31 limit)
            dataBuilder.addServiceData(PARCEL_SERVICE_UUID, packetData)
        } else {
            // Heartbeat/presence beacon
            dataBuilder.addServiceUuid(PARCEL_SERVICE_UUID)
        }

        runCatching {
            advertiser?.startAdvertising(settings, dataBuilder.build(), advertiseCallback)
        }.onFailure {
            Log.e(TAG, "Failed to start BLE advertising", it)
        }
    }

    fun stopAdvertise() {
        if (!hasAdvertisePermission()) return
        if (!isAdvertising) return
        runCatching {
            advertiser?.stopAdvertising(advertiseCallback)
            isAdvertising = false
            Log.d(TAG, "BLE advertising stopped")
        }.onFailure {
            Log.e(TAG, "Failed to stop BLE advertising", it)
        }
    }

    private fun pauseRadio() {
        stopScan()
        stopAdvertise()
        queueWorkerJob?.cancel()
    }

    fun stop() {
        isRunning = false
        pauseRadio()
        queueScope.cancel()
        runCatching { context.unregisterReceiver(bluetoothStateReceiver) }
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasAdvertisePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
        }
    }
}
