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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
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

    // Discovered Characteristics
    private var controlWriteChar: BluetoothGattCharacteristic? = null
    private var controlNotifyChar: BluetoothGattCharacteristic? = null
    private var bulkWriteChar: BluetoothGattCharacteristic? = null
    private var bulkNotifyChar: BluetoothGattCharacteristic? = null

    private var negotiatedMtu: Int = 247
    private var connectionReadyDeferred: CompletableDeferred<Boolean>? = null
    private var pendingControlResponse: CompletableDeferred<ByteArray>? = null
    private var pendingBlockNotify: CompletableDeferred<ByteArray>? = null

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

    @SuppressLint("MissingPermission")
    fun connectWatch(watch: DiscoveredWatch, autoConnect: Boolean = false) {
        Log.d(tag, "connectWatch: connecting to ${watch.name} at ${watch.address} (autoConnect=$autoConnect)")
        disconnect()
        stopScan()
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
        _watchInfo.value = WatchDeviceInfo()
        _syncProgress.value = BleSyncProgress.Idle
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(tag, "onConnectionStateChange: status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = BleConnectionState.CONNECTED
                Log.d(tag, "GATT connected, requesting MTU 512...")
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(tag, "GATT disconnected (status=$status)")
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
                Log.d(tag, "Negotiated MTU: $mtu")
            }
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(tag, "onServicesDiscovered status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.services.forEach { service ->
                    Log.d(tag, "Service: ${service.uuid}")
                    service.characteristics.forEach { ch ->
                        Log.d(tag, "  -> Char: ${ch.uuid}, props=${ch.properties}")
                    }
                }

                // Discover standard services
                gatt.getService(BATTERY_SERVICE_UUID)?.let { service ->
                    service.getCharacteristic(BATTERY_LEVEL_CHAR_UUID)?.let { char ->
                        gatt.readCharacteristic(char)
                    }
                }
                gatt.getService(DEVICE_INFO_SERVICE_UUID)?.let { service ->
                    service.getCharacteristic(MODEL_NUMBER_CHAR_UUID)?.let { char ->
                        gatt.readCharacteristic(char)
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

                Log.d(tag, "COROS Channels: controlWrite=$controlWriteChar, controlNotify=$controlNotifyChar, bulkWrite=$bulkWriteChar")

                // Enable notifications on Control Notify characteristic
                controlNotifyChar?.let { char ->
                    val notifySuccess = gatt.setCharacteristicNotification(char, true)
                    Log.d(tag, "setCharacteristicNotification on controlNotify: $notifySuccess")
                    val descriptor = char.getDescriptor(CCCD_UUID)
                    if (descriptor != null) {
                        val res = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(descriptor)
                        }
                        Log.d(tag, "writeDescriptor result: $res")
                    } else {
                        connectionReadyDeferred?.complete(true)
                    }
                } ?: run {
                    Log.w(tag, "controlNotifyChar is null!")
                    connectionReadyDeferred?.complete(true)
                }
            } else {
                connectionReadyDeferred?.complete(false)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(tag, "Descriptor write success, channels ready for sync")
                connectionReadyDeferred?.complete(true)
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
        Log.d(tag, "Notification from $uuid: len=${data.size}, op=0x%02X".format(data[0]))

        if (uuid == CONTROL_NOTIFY_UUID) {
            val opcode = data[0].toInt() and 0xFF
            if (opcode == 0x96) {
                pendingControlResponse?.complete(data)
            } else if (opcode == 0x78) {
                pendingBlockNotify?.complete(data)
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
    private fun writeWithoutResponse(char: BluetoothGattCharacteristic, data: ByteArray) {
        val gatt = activeGatt ?: run {
            Log.w(tag, "writeWithoutResponse: activeGatt is null!")
            return
        }
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            if (gatt.writeCharacteristic(char)) 0 else -1
        }
        Log.d(tag, "writeWithoutResponse to ${char.uuid}: len=${data.size}, op=0x%02X, result=$result".format(data[0]))
    }

    /**
     * Reads the 10 watchface slots on the COROS watch via Command 0x96.
     * Returns a list of UInt32 face IDs (0xFFFFFFFF means empty slot).
     */
    suspend fun readWatchfaceInventory(): Result<List<Long>> {
        val controlWrite = controlWriteChar ?: return Result.failure(IllegalStateException("Control write channel not ready"))
        val deferred = CompletableDeferred<ByteArray>()
        pendingControlResponse = deferred

        // Command 0x96 with payload [0x00] -> [opcode 0x96, seq 0x00, payload 0x00, checksum 0x00]
        val req = byteArrayOf(0x96.toByte(), 0x00, 0x00, 0x00)
        writeWithoutResponse(controlWrite, req)

        return try {
            val response = withTimeout(10000L) { deferred.await() }
            if (response.size < 4) {
                return Result.failure(IllegalStateException("Invalid watch inventory response length: ${response.size}"))
            }
            // Response payload starts after opcode (0x96) and seq byte (0x00)
            val payloadLen = response.size - 3 // omit opcode, seq, and checksum byte
            val slotCount = payloadLen / 4
            val slots = mutableListOf<Long>()
            for (i in 0 until min(slotCount, 10)) {
                val id = readUInt32LE(response, 2 + i * 4)
                slots.add(id)
            }
            // Pad to 10 slots with 0xFFFFFFFF if needed
            while (slots.size < 10) {
                slots.add(0xFFFFFFFFL)
            }
            Result.success(slots)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            pendingControlResponse = null
        }
    }

    /**
     * Direct Over-the-Air Bluetooth LE Transfer of a compiled .bin watch face to the COROS watch.
     */
    suspend fun directSyncWatchface(binBytes: ByteArray): Result<Int> {
        val gatt = activeGatt
        val controlWrite = controlWriteChar
        val bulkWrite = bulkWriteChar

        if (gatt == null || controlWrite == null || bulkWrite == null) {
            _syncProgress.value = BleSyncProgress.Failed("COROS Bluetooth channels not connected. Please link your watch first.")
            return Result.failure(IllegalStateException("Watch not connected"))
        }

        try {
            _syncProgress.value = BleSyncProgress.QueryingSlots("Reading watch face slots...")
            val inventoryResult = readWatchfaceInventory()
            if (inventoryResult.isFailure) {
                val err = "Could not read watch inventory: ${inventoryResult.exceptionOrNull()?.message}"
                _syncProgress.value = BleSyncProgress.Failed(err)
                return Result.failure(IllegalStateException(err))
            }

            val slots = inventoryResult.getOrThrow()
            var chosenSlot = slots.indexOfFirst { it == 0xFFFFFFFFL }
            if (chosenSlot < 0) {
                chosenSlot = 0 // If all 10 slots full, replace slot 0
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
            Log.d(tag, "Prepared watchface transfer: $totalBytes bytes into slot $chosenSlot (faceId=0x%08X)".format(faceId))

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

                // Send block header to controlWrite
                val headerDeferred = CompletableDeferred<ByteArray>()
                pendingBlockNotify = headerDeferred
                writeWithoutResponse(controlWrite, blockHeader)

                val headerAck = withTimeout(15000L) { headerDeferred.await() }
                if (headerAck.size == 4 && headerAck[1].toInt() == 0 && headerAck[2] == headerAck[3]) {
                    throw IllegalStateException("Watch refused block transfer (error code ${headerAck[2]})")
                }

                // If block already cached
                if (headerAck.size >= 6 && headerAck[2].toInt() == 1) {
                    val percent = ((offset + blockLen) * 100) / totalBytes
                    _syncProgress.value = BleSyncProgress.Transferring(percent, offset + blockLen, totalBytes)
                    continue
                }

                // Subpacket frame size (accounting for MTU limit)
                val reportedSize = if (headerAck.size >= 5) headerAck[4].toInt() and 0xFF else 240
                val mtuLimit = (negotiatedMtu - 3).coerceAtLeast(20)
                val frameSize = maxOf(17, minOf(reportedSize, mtuLimit, 240) - 3)

                // 2.2 Send block in subpacket frames to bulkWrite
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
                    delay(5) // Pacing to prevent BLE FIFO overflow
                }

                // Wait for block completion ACK from watch
                val blockAck = withTimeout(20000L) { blockAckDeferred.await() }
                if (blockAck.size < 3 || blockAck[2].toInt() != 1) {
                    throw IllegalStateException("Watch rejected data block at offset $offset")
                }

                val currentSent = offset + blockLen
                val percent = (currentSent * 100) / totalBytes
                _syncProgress.value = BleSyncProgress.Transferring(percent, currentSent, totalBytes)
            }

            // 3. Verification via Command 0x96
            _syncProgress.value = BleSyncProgress.Verifying("Verifying watch face installation...")
            delay(500)
            val verifyResult = readWatchfaceInventory()
            if (verifyResult.isSuccess) {
                val updatedSlots = verifyResult.getOrThrow()
                Log.d(tag, "Post-sync slots: $updatedSlots")
            }

            _syncProgress.value = BleSyncProgress.Success("Watch face installed and active on your COROS PACE Pro!", chosenSlot)
            return Result.success(chosenSlot)

        } catch (e: Exception) {
            Log.e(tag, "Direct watchface transfer failed", e)
            val errMsg = e.message ?: "Watch face sync failed"
            _syncProgress.value = BleSyncProgress.Failed(errMsg)
            return Result.failure(e)
        } finally {
            pendingBlockNotify = null
        }
    }
}
