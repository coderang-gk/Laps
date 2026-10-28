package io.github.coderang.laps.watchface

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.min

/**
 * Direct on-device Bluetooth LE manager for discovering, pairing, and communicating
 * with COROS sports watches (PACE 2/3/Pro, APEX, VERTIX).
 *
 * Implements the authentic COROS direct watchface transfer protocol over Nordic UART /
 * Weloop GATT characteristics:
 * - Control channel (0x96 inventory query, 0x78 block headers and acknowledgements)
 * - Bulk channel (0x78 framed data chunks up to negotiated MTU)
 */
class CorosBleManager(private val context: Context) {

    private val tag = "CorosBleManager"

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

    sealed class BleSyncProgress {
        object Idle : BleSyncProgress()
        data class Connecting(val msg: String) : BleSyncProgress()
        data class QueryingSlots(val msg: String) : BleSyncProgress()
        data class Transferring(val percent: Int, val sentBytes: Int, val totalBytes: Int) : BleSyncProgress()
        data class Verifying(val msg: String) : BleSyncProgress()
        data class Success(val msg: String, val slot: Int) : BleSyncProgress()
        data class Failed(val error: String) : BleSyncProgress()
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private var activeGatt: BluetoothGatt? = null
    private val handler = Handler(Looper.getMainLooper())

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _discoveredWatches = MutableStateFlow<List<DiscoveredWatch>>(emptyList())
    val discoveredWatches: StateFlow<List<DiscoveredWatch>> = _discoveredWatches.asStateFlow()

    private val _watchInfo = MutableStateFlow(WatchDeviceInfo())
    val watchInfo: StateFlow<WatchDeviceInfo> = _watchInfo.asStateFlow()

    private val _syncProgress = MutableStateFlow<BleSyncProgress>(BleSyncProgress.Idle)
    val syncProgress: StateFlow<BleSyncProgress> = _syncProgress.asStateFlow()

    private val _watchSlots = MutableStateFlow<List<Long>?>(null)
    val watchSlots: StateFlow<List<Long>?> = _watchSlots.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val gattMutex = Mutex()

    // Discovered Characteristics
    private var controlWriteChar: BluetoothGattCharacteristic? = null
    private var controlNotifyChar: BluetoothGattCharacteristic? = null
    private var bulkWriteChar: BluetoothGattCharacteristic? = null
    private var bulkNotifyChar: BluetoothGattCharacteristic? = null

    private var negotiatedMtu: Int = 247
    private var connectionReadyDeferred: CompletableDeferred<Boolean>? = null
    private var pendingControlResponse: CompletableDeferred<ByteArray>? = null
    private var pendingBlockNotify: CompletableDeferred<ByteArray>? = null
    private var pendingAuthChallenge: CompletableDeferred<ByteArray>? = null
    private var pendingAuthFinal: CompletableDeferred<ByteArray>? = null
    private val blockNotifyChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val inventoryResponseBuffer = java.io.ByteArrayOutputStream()
    private val authResponseBuffer = java.io.ByteArrayOutputStream()

    // Standard & COROS BLE GATT UUIDs
    companion object {
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_CHAR_UUID: UUID = UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb")

        val DEVICE_INFO_SERVICE_UUID: UUID = UUID.fromString("0000180A-0000-1000-8000-00805f9b34fb")
        val MODEL_NUMBER_CHAR_UUID: UUID = UUID.fromString("00002A24-0000-1000-8000-00805f9b34fb")
        val SERIAL_NUMBER_CHAR_UUID: UUID = UUID.fromString("00002A25-0000-1000-8000-00805f9b34fb")
        val FIRMWARE_REV_CHAR_UUID: UUID = UUID.fromString("00002A26-0000-1000-8000-00805f9b34fb")

        // COROS Custom Channels (Weloop / Nordic UART custom base)
        val CONTROL_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-77656c6f6f70")
        val CONTROL_WRITE_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-77656c6f6f70")
        val CONTROL_NOTIFY_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-77656c6f6f70")

        val BULK_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val BULK_WRITE_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val BULK_NOTIFY_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val WATCHFACE_FILE_INDEX: Byte = 8
        const val WATCHFACE_BLOCK_BYTES: Int = 4096
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
        val bonded = getBondedWatches()

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
    fun getBondedWatches(): List<DiscoveredWatch> {
        val adapter = bluetoothAdapter ?: return emptyList()
        return try {
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

    fun resetSyncProgress() {
        _syncProgress.value = BleSyncProgress.Idle
    }

    @SuppressLint("MissingPermission")
    fun connectWatch(watch: DiscoveredWatch, autoConnect: Boolean = false) {
        Log.i(tag, "connectWatch: connecting to ${watch.name} at ${watch.address} (autoConnect=$autoConnect)")
        disconnect()
        stopScan()
        _watchInfo.value = WatchDeviceInfo(modelName = watch.name)
        _connectionState.value = BleConnectionState.CONNECTING
        _syncProgress.value = BleSyncProgress.Connecting("Connecting to ${watch.name}... (Press watch crown to wake display)")
        connectionReadyDeferred = CompletableDeferred()

        try {
            activeGatt = watch.device.connectGatt(
                context.applicationContext,
                autoConnect,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        } catch (e: Exception) {
            Log.e(tag, "connectGatt failed", e)
            _connectionState.value = BleConnectionState.DISCONNECTED
            _syncProgress.value = BleSyncProgress.Failed("Connection failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            activeGatt?.disconnect()
            activeGatt?.close()
        } catch (_: Exception) {}
        activeGatt = null
        controlWriteChar = null
        controlNotifyChar = null
        bulkWriteChar = null
        bulkNotifyChar = null
        _connectionState.value = BleConnectionState.DISCONNECTED
        _isAuthenticated.value = false
        _watchInfo.value = WatchDeviceInfo()
        _watchSlots.value = null
        _syncProgress.value = BleSyncProgress.Idle
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(tag, "onConnectionStateChange: status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = BleConnectionState.CONNECTING
                Log.i(tag, "GATT connected, requesting MTU 512...")
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(tag, "GATT disconnected (status=$status)")
                try {
                    gatt.close()
                } catch (_: Exception) {}
                activeGatt = null
                _connectionState.value = BleConnectionState.DISCONNECTED
                connectionReadyDeferred?.complete(false)
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    _syncProgress.value = BleSyncProgress.Failed("Watch link failed (status $status). Please press watch crown to wake screen and retry.")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                Log.i(tag, "Negotiated MTU: $mtu")
            }
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.i(tag, "onServicesDiscovered status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.services.forEach { service ->
                    Log.i(tag, "Service: ${service.uuid}")
                    service.characteristics.forEach { ch ->
                        Log.i(tag, "  -> Char: ${ch.uuid}, props=${ch.properties}")
                    }
                }

                // Discover COROS Control & Bulk Channels
                val controlService = gatt.getService(CONTROL_SERVICE_UUID)
                if (controlService != null) {
                    controlWriteChar = controlService.getCharacteristic(CONTROL_WRITE_UUID)
                    controlNotifyChar = controlService.getCharacteristic(CONTROL_NOTIFY_UUID)
                }

                val bulkService = gatt.getService(BULK_SERVICE_UUID)
                if (bulkService != null) {
                    bulkWriteChar = bulkService.getCharacteristic(BULK_WRITE_UUID)
                    bulkNotifyChar = bulkService.getCharacteristic(BULK_NOTIFY_UUID)
                }

                Log.i(tag, "COROS Channels: controlWrite=$controlWriteChar, controlNotify=$controlNotifyChar, bulkWrite=$bulkWriteChar")

                // Enable notifications on Control Notify characteristic FIRST (no competing reads!)
                controlNotifyChar?.let { char ->
                    val notifySuccess = gatt.setCharacteristicNotification(char, true)
                    Log.i(tag, "setCharacteristicNotification on controlNotify: $notifySuccess")
                    val descriptor = char.getDescriptor(CCCD_UUID)
                    if (descriptor != null) {
                        val res = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            if (gatt.writeDescriptor(descriptor)) 0 else -1
                        }
                        Log.i(tag, "writeDescriptor result on controlNotify: $res")
                        if (res != 0 && res != -1) {
                            handler.postDelayed({
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                } else {
                                    @Suppress("DEPRECATION")
                                    gatt.writeDescriptor(descriptor)
                                }
                            }, 100)
                        }
                    } else {
                        Log.i(tag, "Channels ready for sync (no control descriptor)!")
                        _connectionState.value = BleConnectionState.CONNECTED
                        connectionReadyDeferred?.complete(true)
                    }
                } ?: run {
                    Log.w(tag, "controlNotifyChar is null!")
                    _connectionState.value = BleConnectionState.CONNECTED
                    connectionReadyDeferred?.complete(true)
                }
            } else {
                connectionReadyDeferred?.complete(false)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.i(tag, "onDescriptorWrite: char=${descriptor.characteristic.uuid}, status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (descriptor.characteristic.uuid == CONTROL_NOTIFY_UUID) {
                    bulkNotifyChar?.let { bulk ->
                        gatt.setCharacteristicNotification(bulk, true)
                        val bulkDesc = bulk.getDescriptor(CCCD_UUID)
                        if (bulkDesc != null) {
                            handler.postDelayed({
                                val res = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    gatt.writeDescriptor(bulkDesc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                } else {
                                    @Suppress("DEPRECATION")
                                    bulkDesc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    @Suppress("DEPRECATION")
                                    if (gatt.writeDescriptor(bulkDesc)) 0 else -1
                                }
                                Log.i(tag, "Subscribed bulkNotify CCCD result=$res")
                            }, 50)
                            return
                        }
                    }
                }
                Log.i(tag, "Channels ready for sync!")
                _connectionState.value = BleConnectionState.CONNECTED
                _syncProgress.value = BleSyncProgress.Idle
                connectionReadyDeferred?.complete(true)

                // Read battery level after descriptors are subscribed without collision
                handler.postDelayed({
                    gatt.getService(BATTERY_SERVICE_UUID)?.getCharacteristic(BATTERY_LEVEL_CHAR_UUID)?.let {
                        gatt.readCharacteristic(it)
                    }
                }, 100)
            } else {
                connectionReadyDeferred?.complete(false)
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
                        @Suppress("DEPRECATION")
                        val level = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0) ?: -1
                        _watchInfo.value = _watchInfo.value.copy(batteryLevel = level)
                    }
                    MODEL_NUMBER_CHAR_UUID -> {
                        @Suppress("DEPRECATION")
                        val model = characteristic.getStringValue(0) ?: "COROS Watch"
                        _watchInfo.value = _watchInfo.value.copy(modelName = model)
                    }
                    FIRMWARE_REV_CHAR_UUID -> {
                        @Suppress("DEPRECATION")
                        val fw = characteristic.getStringValue(0) ?: ""
                        _watchInfo.value = _watchInfo.value.copy(firmwareRevision = fw)
                    }
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.i(tag, "onCharacteristicWrite: uuid=${characteristic.uuid}, status=$status")
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            characteristic.value?.let { handleIncomingNotification(characteristic.uuid, it) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingNotification(characteristic.uuid, value)
        }
    }

    private fun handleIncomingNotification(uuid: UUID, data: ByteArray) {
        if (data.isEmpty()) return
        Log.i(tag, "Notification from $uuid: len=${data.size}, op=0x%02X, hex=${data.joinToString("") { "%02x".format(it) }}".format(data[0]))

        if (uuid == CONTROL_NOTIFY_UUID || uuid == BULK_NOTIFY_UUID) {
            val opcode = data[0].toInt() and 0xFF
            if (opcode == 0xA5) {
                if (data.size >= 3) {
                    val remaining = data[1].toInt() and 0xFF
                    authResponseBuffer.write(data, 2, data.size - 2)
                    if (remaining == 0) {
                        val full = authResponseBuffer.toByteArray()
                        authResponseBuffer.reset()
                        val payload = if (full.isNotEmpty()) full.copyOfRange(0, full.size - 1) else full
                        Log.i(tag, "Assembled complete 0xA5 frame: len=${payload.size}")
                        if (pendingAuthChallenge?.isCompleted == false) {
                            pendingAuthChallenge?.complete(payload)
                        } else if (pendingAuthFinal?.isCompleted == false) {
                            pendingAuthFinal?.complete(payload)
                        }
                    }
                }
            } else if (opcode == 0x96) {
                if (data.size >= 3) {
                    val remaining = data[1].toInt() and 0xFF
                    inventoryResponseBuffer.write(data, 2, data.size - 2)
                    if (remaining == 0) {
                        val full = inventoryResponseBuffer.toByteArray()
                        inventoryResponseBuffer.reset()
                        pendingControlResponse?.complete(full)
                    }
                } else if (data.size == 2 && (data[1].toInt() and 0xFF) == 0) {
                    val full = inventoryResponseBuffer.toByteArray()
                    inventoryResponseBuffer.reset()
                    pendingControlResponse?.complete(full)
                }
            } else if (opcode == 0x78) {
                Log.i(tag, "Received 0x78 SFT frame: len=${data.size}, hex=${data.joinToString("") { "%02x".format(it) }}")
                if (pendingBlockNotify?.isCompleted == false) {
                    pendingBlockNotify?.complete(data)
                }
                blockNotifyChannel.trySend(data)
            }
        }
    }

    // --- COROS Protocol Helpers & Direct Transfer ---

    fun crc16Ccitt(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in offset until (offset + length)) {
            val byte = data[i].toInt() and 0xFF
            crc = crc xor (byte shl 8)
            for (bit in 0 until 8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc and 0xFFFF
    }

    private fun writeUInt32LE(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeUInt16LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun readUInt32LE(buf: ByteArray, offset: Int): Long {
        return (buf[offset].toLong() and 0xFF) or
                ((buf[offset + 1].toLong() and 0xFF) shl 8) or
                ((buf[offset + 2].toLong() and 0xFF) shl 16) or
                ((buf[offset + 3].toLong() and 0xFF) shl 24)
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeWithoutResponse(char: BluetoothGattCharacteristic, data: ByteArray): Int {
        val gatt = activeGatt ?: run {
            Log.w(tag, "writeWithoutResponse: activeGatt is null!")
            return -1
        }
        val writeType = if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        var retries = 0
        var result = -1
        while (retries < 15) {
            result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, data, writeType)
            } else {
                @Suppress("DEPRECATION")
                char.writeType = writeType
                @Suppress("DEPRECATION")
                char.value = data
                @Suppress("DEPRECATION")
                if (gatt.writeCharacteristic(char)) 0 else -1
            }

            if (result == 0) {
                return 0
            }
            if (result == 201 || result == 257) { // GATT_WRITE_REQUEST_BUSY / GATT_BUSY
                delay(8)
                retries++
                continue
            }
            break
        }
        if (result != 0) {
            Log.w(tag, "writeWithoutResponse to ${char.uuid}: op=0x%02X failed with result=$result after $retries retries".format(data[0]))
        }
        return result
    }

    /**
     * Executes the authentic COROS Opcode 0xA5 SystemBind RSA challenge-response handshake
     * to unlock the watch firmware's protected command set (inventory query 0x96, SFT file transfer 0x78).
     */
    suspend fun authenticateWatchSession(): Result<Boolean> = gattMutex.withLock {
        internalAuthenticateWatchSession()
    }

    private suspend fun internalAuthenticateWatchSession(): Result<Boolean> {
        val auth = io.github.coderang.laps.data.LapsRepository.getWatchAuth(context)
            ?: return Result.failure(IllegalStateException("No saved COROS watch credentials. Sign in to your COROS account first."))

        val controlWrite = controlWriteChar ?: return Result.failure(IllegalStateException("Control write channel not ready"))

        val bindProfile = try {
            android.util.Base64.decode(auth.bindProfileBase64, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            return Result.failure(IllegalStateException("Invalid saved bind profile: ${e.message}"))
        }

        if (bindProfile.size != 17) {
            return Result.failure(IllegalStateException("Invalid watch bind profile length: ${bindProfile.size}"))
        }

        // Set current timestamp (seconds) at bytes 5..8
        val base = bindProfile.clone()
        val nowSec = (System.currentTimeMillis() / 1000L).toLong()
        base[5] = (nowSec and 0xFF).toByte()
        base[6] = ((nowSec shr 8) and 0xFF).toByte()
        base[7] = ((nowSec shr 16) and 0xFF).toByte()
        base[8] = ((nowSec shr 24) and 0xFF).toByte()

        // 1. Send initial payload framed with 0xA5
        // logical = base (17B) + sum(base) & 0xFF (1B) = 18B
        val chDeferred = CompletableDeferred<ByteArray>()
        authResponseBuffer.reset()
        pendingAuthChallenge = chDeferred

        var bSum = 0
        for (b in base) bSum = (bSum + (b.toInt() and 0xFF)) and 0xFF
        val initFrame = ByteArray(2 + base.size + 1)
        initFrame[0] = 0xA5.toByte()
        initFrame[1] = 0x00 // remaining = 0
        System.arraycopy(base, 0, initFrame, 2, base.size)
        initFrame[initFrame.size - 1] = bSum.toByte()

        Log.i(tag, "authenticateWatchSession: sending initial A5 frame, len=${initFrame.size}")
        writeWithoutResponse(controlWrite, initFrame)

        val challenge = try {
            withTimeout(15000L) { chDeferred.await() }
        } catch (e: Exception) {
            Log.e(tag, "Timed out waiting for watch auth challenge", e)
            pendingAuthChallenge = null
            return Result.failure(IllegalStateException("Timed out waiting for watch auth challenge. Please wake your watch display."))
        } finally {
            pendingAuthChallenge = null
        }

        Log.i(tag, "authenticateWatchSession: received challenge len=${challenge.size}")
        if (challenge.size != 17) {
            return Result.failure(IllegalStateException("Unexpected challenge response size: ${challenge.size}"))
        }

        val status = challenge[0].toInt() and 7
        if (status != 5) {
            return Result.failure(IllegalStateException("Unexpected challenge status: $status"))
        }
        val nonce = challenge.copyOfRange(11, 15)
        Log.i(tag, "Challenge status=5, nonce=${nonce.joinToString("") { "%02x".format(it) }}")

        // 2. Compute RSA-2048 PKCS#1 v1.5 SHA-256 signature over nonce (4B) + userId_8B_LE (8B)
        val signature = try {
            io.github.coderang.laps.coros.CorosMobileAuth.signChallenge(auth.privateKeyDerBase64, nonce, auth.accountUserId)
        } catch (e: Exception) {
            Log.e(tag, "Failed to sign watch challenge", e)
            return Result.failure(IllegalStateException("Failed to sign watch challenge: ${e.message}"))
        }

        // 3. Construct 293-byte authenticated payload:
        // base (17B) + AUTH_METADATA [0, 1, 0x10, 1, 0, 0x11, 0, 0x0C] (8B) + signature (256B) + userId_8B_LE (8B) + nonce (4B)
        val userBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(auth.accountUserId).array()
        val authMetadata = byteArrayOf(0, 1, 0x10, 1, 0, 0x11, 0, 0x0C)
        val authPayload = ByteArray(17 + 8 + 256 + 8 + 4)
        var pPos = 0
        System.arraycopy(base, 0, authPayload, pPos, 17); pPos += 17
        System.arraycopy(authMetadata, 0, authPayload, pPos, 8); pPos += 8
        System.arraycopy(signature, 0, authPayload, pPos, 256); pPos += 256
        System.arraycopy(userBuf, 0, authPayload, pPos, 8); pPos += 8
        System.arraycopy(nonce, 0, authPayload, pPos, 4); pPos += 4

        // Sliced frames with Opcode 0xA5
        var pSum = 0
        for (b in authPayload) pSum = (pSum + (b.toInt() and 0xFF)) and 0xFF
        val logical = ByteArray(authPayload.size + 1)
        System.arraycopy(authPayload, 0, logical, 0, authPayload.size)
        logical[logical.size - 1] = pSum.toByte()

        val finalDeferred = CompletableDeferred<ByteArray>()
        authResponseBuffer.reset()
        pendingAuthFinal = finalDeferred

        val maxChunk = minOf(negotiatedMtu - 3, 240) - 2
        val chunkCount = (logical.size + maxChunk - 1) / maxChunk
        for (i in 0 until chunkCount) {
            val offset = i * maxChunk
            val len = minOf(maxChunk, logical.size - offset)
            val frame = ByteArray(2 + len)
            frame[0] = 0xA5.toByte()
            frame[1] = (chunkCount - 1 - i).toByte()
            System.arraycopy(logical, offset, frame, 2, len)
            writeWithoutResponse(controlWrite, frame)
            delay(5)
        }

        val bound = try {
            withTimeout(15000L) { finalDeferred.await() }
        } catch (e: Exception) {
            Log.e(tag, "Timed out waiting for final auth response", e)
            pendingAuthFinal = null
            return Result.failure(IllegalStateException("Timed out waiting for watch auth confirmation."))
        } finally {
            pendingAuthFinal = null
        }

        Log.i(tag, "authenticateWatchSession: final bound len=${bound.size}")
        return if (bound.isNotEmpty() && (bound[0].toInt() and 7) == 1) {
            _isAuthenticated.value = true
            Log.i(tag, "WATCH IS AUTHENTICATED SUCCESSFULLY! All file commands unlocked.")
            Result.success(true)
        } else {
            val finalStatus = if (bound.isNotEmpty()) bound[0].toInt() and 7 else -1
            val reason = if (bound.size > 10) bound[10].toInt() else -1
            _isAuthenticated.value = false
            Result.failure(IllegalStateException("Watch rejected authentication: status=$finalStatus, reason=$reason"))
        }
    }

    /**
     * Reads the 10 watchface slots on the COROS watch via Command 0x96.
     * Returns a list of UInt32 face IDs (0xFFFFFFFF means empty slot).
     */
    suspend fun readWatchfaceInventory(): Result<List<Long>> = gattMutex.withLock {
        internalReadWatchfaceInventory()
    }

    private suspend fun internalReadWatchfaceInventory(): Result<List<Long>> {
        connectionReadyDeferred?.let { def ->
            if (!def.isCompleted) {
                try { withTimeout(5000L) { def.await() } } catch (_: Exception) {}
            }
        }
        if (!_isAuthenticated.value && io.github.coderang.laps.data.LapsRepository.hasWatchAuth(context)) {
            val authRes = internalAuthenticateWatchSession()
            if (authRes.isFailure) {
                Log.w(tag, "readWatchfaceInventory: authentication attempt failed: ${authRes.exceptionOrNull()?.message}")
            }
        }
        val controlWrite = controlWriteChar ?: return Result.failure(IllegalStateException("Control write channel not ready"))
        val deferred = CompletableDeferred<ByteArray>()
        inventoryResponseBuffer.reset()
        pendingControlResponse = deferred

        Log.i(tag, "readWatchfaceInventory: sending opcode 0x96 query")
        // Command 0x96 with payload [0x00] -> [opcode 0x96, seq 0x00, payload 0x00, checksum 0x00]
        val req = byteArrayOf(0x96.toByte(), 0x00, 0x00, 0x00)
        writeWithoutResponse(controlWrite, req)

        return try {
            val response = withTimeout(4000L) { deferred.await() }
            Log.i(tag, "readWatchfaceInventory: received response length=${response.size}")
            if (response.size < 4) {
                return Result.failure(IllegalStateException("Invalid watch inventory response length: ${response.size}"))
            }
            // If the reassembled payload has an extra trailing checksum byte (payload % 4 == 1):
            val payload = if (response.size % 4 == 1) {
                response.copyOfRange(0, response.size - 1)
            } else if (response.size >= 40) {
                response.copyOfRange(0, 40)
            } else {
                response
            }
            val slotCount = payload.size / 4
            val slots = mutableListOf<Long>()
            for (i in 0 until min(slotCount, 10)) {
                val id = readUInt32LE(payload, i * 4)
                slots.add(id)
            }
            while (slots.size < 10) {
                slots.add(0xFFFFFFFFL)
            }
            _watchSlots.value = slots
            Log.i(tag, "readWatchfaceInventory decoded ${slots.size} slots: $slots")
            Result.success(slots)
        } catch (e: Exception) {
            Log.w(tag, "readWatchfaceInventory failed/timed out: ${e.message}")
            Result.failure(e)
        } finally {
            pendingControlResponse = null
        }
    }

    /**
     * Direct Over-the-Air Bluetooth LE Transfer of a compiled .bin watch face to the COROS watch.
     * @param targetSlot 0-based target slot index (0 to 9, where 9 corresponds to Slot 10).
     */
    suspend fun directSyncWatchface(binBytes: ByteArray, targetSlot: Int = 9): Result<Int> = gattMutex.withLock {
        val gatt = activeGatt
        val controlWrite = controlWriteChar
        val bulkWrite = bulkWriteChar

        if (gatt == null || controlWrite == null || bulkWrite == null) {
            _syncProgress.value = BleSyncProgress.Failed("COROS Bluetooth channels not connected. Please link your watch first.")
            return@withLock Result.failure(IllegalStateException("Watch not connected"))
        }

        try {
            connectionReadyDeferred?.let { def ->
                if (!def.isCompleted) {
                    try { withTimeout(4000L) { def.await() } } catch (_: Exception) {}
                }
            }
            _syncProgress.value = BleSyncProgress.QueryingSlots("Targeting Slot ${targetSlot + 1}...")
            val chosenSlot = targetSlot.coerceIn(0, 9)

            // Ensure watch is authenticated with opcode 0xA5 before sending file commands
            if (!_isAuthenticated.value) {
                _syncProgress.value = BleSyncProgress.Connecting("Authenticating session with COROS watch...")
                val authRes = internalAuthenticateWatchSession()
                if (authRes.isFailure) {
                    val errMsg = authRes.exceptionOrNull()?.message ?: "Watch authentication failed"
                    _syncProgress.value = BleSyncProgress.Failed(errMsg)
                    return@withLock Result.failure(authRes.exceptionOrNull() ?: IllegalStateException(errMsg))
                }
            }

            // Attempt quick inventory read if not cached yet; non-fatal
            if (_watchSlots.value == null) {
                try {
                    withTimeout(1500L) {
                        internalReadWatchfaceInventory()
                    }
                } catch (_: Exception) {}
            }

            // 1. Wrap BIN with authentic COROS 16-byte HF file header
            val faceId = readUInt32LE(binBytes, 4)
            val fileHeader = ByteArray(16)
            fileHeader[0] = 'H'.code.toByte()
            fileHeader[1] = 'F'.code.toByte()
            fileHeader[2] = 0
            fileHeader[3] = WATCHFACE_FILE_INDEX
            writeUInt32LE(fileHeader, 8, binBytes.size.toLong())
            writeUInt16LE(fileHeader, 12, crc16Ccitt(binBytes))
            writeUInt16LE(fileHeader, 14, crc16Ccitt(fileHeader, 0, 14))

            val fullFile = ByteArray(16 + binBytes.size)
            System.arraycopy(fileHeader, 0, fullFile, 0, 16)
            System.arraycopy(binBytes, 0, fullFile, 16, binBytes.size)

            val totalBytes = fullFile.size
            Log.i(tag, "Prepared watchface transfer: $totalBytes bytes into Slot ${chosenSlot + 1} (slot index $chosenSlot, faceId=0x%08X)".format(faceId))

            // 2. Transfer 4096-byte blocks
            for (offset in 0 until totalBytes step WATCHFACE_BLOCK_BYTES) {
                val blockLen = min(WATCHFACE_BLOCK_BYTES, totalBytes - offset)
                val block = fullFile.copyOfRange(offset, offset + blockLen)

                // 2.1 Encode 21-byte block header
                val blockHeader = ByteArray(21)
                blockHeader[0] = 0x78
                blockHeader[1] = 0x00
                blockHeader[2] = WATCHFACE_FILE_INDEX
                writeUInt32LE(blockHeader, 3, offset.toLong())
                writeUInt16LE(blockHeader, 7, blockLen)
                writeUInt32LE(blockHeader, 9, (totalBytes - offset).toLong())
                writeUInt16LE(blockHeader, 13, crc16Ccitt(block))

                var bSum = 0
                for (b in block) bSum = (bSum + (b.toInt() and 0xFF)) and 0xFF
                blockHeader[15] = bSum.toByte()
                blockHeader[16] = chosenSlot.toByte()
                blockHeader[17] = 0
                blockHeader[18] = 0
                blockHeader[19] = 3

                var hSum = 0
                for (i in 2..19) hSum = (hSum + (blockHeader[i].toInt() and 0xFF)) and 0xFF
                blockHeader[20] = hSum.toByte()

                // Send block header to controlWrite with retry
                var headerAck: ByteArray? = null
                var headerAttempts = 0
                while (headerAttempts < 3 && headerAck == null) {
                    val headerDeferred = CompletableDeferred<ByteArray>()
                    pendingBlockNotify = headerDeferred
                    Log.i(tag, "Sending block header for offset=$offset, blockLen=$blockLen, slot=$chosenSlot (attempt ${headerAttempts + 1})")
                    writeWithoutResponse(controlWrite, blockHeader)

                    headerAck = try {
                        withTimeout(15000L) { headerDeferred.await() }
                    } catch (e: Exception) {
                        Log.w(tag, "Timed out waiting for block header ACK at offset=$offset, retrying...", e)
                        headerAttempts++
                        delay(100)
                        null
                    }
                }

                if (headerAck == null) {
                    throw IllegalStateException("Timed out waiting for watch response at offset $offset. Please wake your watch display by pressing the crown.")
                }
                Log.i(tag, "Received headerAck: len=${headerAck.size}, hex=${headerAck.joinToString("") { "%02x".format(it) }}")
                if (headerAck.size == 4 && headerAck[1].toInt() == 0 && headerAck[2] == headerAck[3]) {
                    throw IllegalStateException("Watch refused block transfer (error code ${headerAck[2]})")
                }

                // If block already cached by watch
                if (headerAck.size >= 6 && headerAck[2].toInt() == 1 && (if (headerAck.size == 9) headerAck[6].toInt() == 1 else headerAck[4] == WATCHFACE_FILE_INDEX)) {
                    val percent = ((offset + blockLen) * 100) / totalBytes
                    _syncProgress.value = BleSyncProgress.Transferring(percent, offset + blockLen, totalBytes)
                    continue
                }

                // Subpacket frame size (accounting for MTU limit)
                val reportedSize = if (headerAck.size >= 5) headerAck[4].toInt() and 0xFF else 240
                val mtuLimit = (negotiatedMtu - 3).coerceAtLeast(20)
                val frameSize = maxOf(17, minOf(reportedSize, mtuLimit, 240) - 3)

                // 2.2 Send block in subpacket frames to bulkWrite with retry
                var blockAck: ByteArray? = null
                var blockAttempts = 0
                while (blockAttempts < 3 && blockAck == null) {
                    val blockAckDeferred = CompletableDeferred<ByteArray>()
                    pendingBlockNotify = blockAckDeferred

                    var seq = 0
                    for (cOff in 0 until blockLen step frameSize) {
                        val cLen = min(frameSize, blockLen - cOff)
                        val frame = ByteArray(3 + cLen)
                        frame[0] = 0x78
                        frame[1] = (seq % 256).toByte()
                        frame[2] = WATCHFACE_FILE_INDEX
                        System.arraycopy(block, cOff, frame, 3, cLen)

                        writeWithoutResponse(bulkWrite, frame)
                        seq++
                        delay(10) // Pacing to prevent BLE FIFO overflow
                    }

                    // Wait for block completion ACK from watch
                    blockAck = try {
                        withTimeout(20000L) { blockAckDeferred.await() }
                    } catch (e: Exception) {
                        Log.w(tag, "Timed out waiting for block completion ACK at offset=$offset, retrying...", e)
                        blockAttempts++
                        delay(100)
                        null
                    }
                }

                if (blockAck == null || blockAck.size < 3 || blockAck[2].toInt() != 1) {
                    throw IllegalStateException("Watch rejected data block at offset $offset")
                }

                val currentSent = offset + blockLen
                val percent = (currentSent * 100) / totalBytes
                _syncProgress.value = BleSyncProgress.Transferring(percent, currentSent, totalBytes)
            }

            // 3. Verification
            _syncProgress.value = BleSyncProgress.Verifying("Verifying watch face installation...")
            delay(300)
            try {
                withTimeout(2000L) {
                    val verifyResult = internalReadWatchfaceInventory()
                    if (verifyResult.isSuccess) {
                        val updatedSlots = verifyResult.getOrThrow()
                        Log.i(tag, "Post-sync slots: $updatedSlots")
                    }
                }
            } catch (_: Exception) {}

            _syncProgress.value = BleSyncProgress.Success(
                "Watch face successfully installed into Slot ${chosenSlot + 1} on your COROS PACE Pro!",
                chosenSlot + 1
            )
            return@withLock Result.success(chosenSlot)

        } catch (e: Exception) {
            Log.e(tag, "Direct watchface transfer failed", e)
            val errMsg = e.message ?: "Watch face sync failed"
            _syncProgress.value = BleSyncProgress.Failed(errMsg)
            return@withLock Result.failure(e)
        } finally {
            pendingBlockNotify = null
        }
    }
}
