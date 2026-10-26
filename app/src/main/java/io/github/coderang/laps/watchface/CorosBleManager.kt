package io.github.coderang.laps.watchface

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Direct on-device Bluetooth LE manager for discovering, pairing, and communicating
 * with COROS sports watches (PACE 2/3/Pro, APEX, VERTIX).
 */
class CorosBleManager(private val context: Context) {

    data class DiscoveredWatch(
        val name: String,
        val address: String,
        val rssi: Int,
        val device: BluetoothDevice
    )

    data class WatchDeviceInfo(
        val modelName: String = "",
        val serialNumber: String = "",
        val firmwareRevision: String = "",
        val batteryLevel: Int = -1
    )

    enum class BleConnectionState {
        DISCONNECTED,
        SCANNING,
        CONNECTING,
        CONNECTED
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private var activeGatt: BluetoothGatt? = null
    private val handler = Handler(Looper.getMainLooper())

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _discoveredWatches = MutableStateFlow<List<DiscoveredWatch>>(emptyList())
    val discoveredWatches: StateFlow<List<DiscoveredWatch>> = _discoveredWatches.asStateFlow()

    private val _watchInfo = MutableStateFlow(WatchDeviceInfo())
    val watchInfo: StateFlow<WatchDeviceInfo> = _watchInfo.asStateFlow()

    // Standard BLE GATT UUIDs
    companion object {
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_CHAR_UUID: UUID = UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb")

        val DEVICE_INFO_SERVICE_UUID: UUID = UUID.fromString("0000180A-0000-1000-8000-00805f9b34fb")
        val MODEL_NUMBER_CHAR_UUID: UUID = UUID.fromString("00002A24-0000-1000-8000-00805f9b34fb")
        val SERIAL_NUMBER_CHAR_UUID: UUID = UUID.fromString("00002A25-0000-1000-8000-00805f9b34fb")
        val FIRMWARE_REV_CHAR_UUID: UUID = UUID.fromString("00002A26-0000-1000-8000-00805f9b34fb")
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val scanRecordName = result.scanRecord?.deviceName
            val name = try {
                device.name ?: scanRecordName ?: "Unknown Device"
            } catch (_: SecurityException) {
                scanRecordName ?: "COROS Watch"
            }

            // Filter for COROS watches (PACE, APEX, VERTIX)
            val isCoros = name.contains("COROS", ignoreCase = true) ||
                    name.contains("PACE", ignoreCase = true) ||
                    name.contains("APEX", ignoreCase = true) ||
                    name.contains("VERTIX", ignoreCase = true)

            if (isCoros) {
                val discovered = DiscoveredWatch(
                    name = name,
                    address = device.address,
                    rssi = result.rssi,
                    device = device
                )
                val current = _discoveredWatches.value.toMutableList()
                val existingIndex = current.indexOfFirst { it.address == device.address }
                if (existingIndex >= 0) {
                    current[existingIndex] = discovered
                } else {
                    current.add(discovered)
                }
                _discoveredWatches.value = current
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _connectionState.value = BleConnectionState.DISCONNECTED
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan(timeoutMillis: Long = 12000L) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _connectionState.value = BleConnectionState.DISCONNECTED
            return
        }

        val scanner = adapter.bluetoothLeScanner ?: return

        // Pre-populate with already paired/bonded COROS devices
        val bonded = try {
            adapter.bondedDevices?.filter {
                val n = it.name ?: ""
                n.contains("COROS", ignoreCase = true) ||
                        n.contains("PACE", ignoreCase = true) ||
                        n.contains("APEX", ignoreCase = true) ||
                        n.contains("VERTIX", ignoreCase = true)
            }?.map {
                DiscoveredWatch(
                    name = it.name ?: "COROS Watch (Paired)",
                    address = it.address,
                    rssi = -45,
                    device = it
                )
            } ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }

        _discoveredWatches.value = bonded
        _connectionState.value = BleConnectionState.SCANNING

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(emptyList(), settings, scanCallback)
            handler.postDelayed({ stopScan() }, timeoutMillis)
        } catch (_: SecurityException) {
            _connectionState.value = BleConnectionState.DISCONNECTED
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (_connectionState.value == BleConnectionState.SCANNING) {
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (_: SecurityException) {}
            _connectionState.value = BleConnectionState.DISCONNECTED
        }
    }

    @SuppressLint("MissingPermission")
    fun connectWatch(watch: DiscoveredWatch) {
        stopScan()
        _connectionState.value = BleConnectionState.CONNECTING

        try {
            activeGatt = watch.device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        } catch (_: SecurityException) {
            _connectionState.value = BleConnectionState.DISCONNECTED
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            activeGatt?.disconnect()
            activeGatt?.close()
        } catch (_: SecurityException) {}
        activeGatt = null
        _connectionState.value = BleConnectionState.DISCONNECTED
        _watchInfo.value = WatchDeviceInfo()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = BleConnectionState.CONNECTED
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Read Battery Level
                gatt.getService(BATTERY_SERVICE_UUID)?.let { service ->
                    service.getCharacteristic(BATTERY_LEVEL_CHAR_UUID)?.let { char ->
                        gatt.readCharacteristic(char)
                    }
                }
                // Read Model Name
                gatt.getService(DEVICE_INFO_SERVICE_UUID)?.let { service ->
                    service.getCharacteristic(MODEL_NUMBER_CHAR_UUID)?.let { char ->
                        gatt.readCharacteristic(char)
                    }
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (characteristic.uuid) {
                    BATTERY_LEVEL_CHAR_UUID -> {
                        val level = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0) ?: -1
                        _watchInfo.value = _watchInfo.value.copy(batteryLevel = level)
                    }
                    MODEL_NUMBER_CHAR_UUID -> {
                        val model = characteristic.getStringValue(0) ?: "COROS Watch"
                        _watchInfo.value = _watchInfo.value.copy(modelName = model)
                    }
                    FIRMWARE_REV_CHAR_UUID -> {
                        val fw = characteristic.getStringValue(0) ?: ""
                        _watchInfo.value = _watchInfo.value.copy(firmwareRevision = fw)
                    }
                }
            }
        }
    }
}
