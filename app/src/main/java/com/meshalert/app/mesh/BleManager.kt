package com.meshalert.app.mesh

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

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
    }

    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    private var isAdvertising = false
    private var isScanning = false

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

    fun startAdvertise(packetData: ByteArray? = null) {
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

    fun broadcastPacket(packetData: ByteArray) {
        startAdvertise(packetData)
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

    fun stop() {
        stopScan()
        stopAdvertise()
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
