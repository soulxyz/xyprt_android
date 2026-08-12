package io.github.soulxyz.xyprt.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * BLE GATT transport for X1 revisions that expose the Qring protocol over FF00.
 *
 * The values intentionally follow the supplied, device-tested web implementation:
 *   service FF00, write FF02, receive FF01, optional side-channel FF03.
 * FF02 is written WITH response and payloads are capped at 133 bytes. The tested printer
 * accepts 133 bytes but fails at 134, so a larger negotiated MTU never increases that cap.
 */
@SuppressLint("MissingPermission")
class BlePrinterConnection private constructor(
    private val context: Context,
    private val device: BluetoothDevice,
    private val log: (String) -> Unit,
) : PrinterConnection {

    override val transport = PrinterTransport.BLE
    override val address: String get() = device.address
    override val isConnected: Boolean get() = gatt?.let { connected } == true
    override var chunkSize: Int = SAFE_MIN_CHUNK
        private set
    override val chunkDelayMs: Long get() = if (writeWithResponse) 0L else 25L

    @Volatile private var connected = false
    @Volatile private var closed = false
    @Volatile private var mtu = 23
    @Volatile private var writeWithResponse = true

    private var gatt: BluetoothGatt? = null
    private lateinit var writeCharacteristic: BluetoothGattCharacteristic
    private lateinit var notifyCharacteristic: BluetoothGattCharacteristic
    private var auxCharacteristic: BluetoothGattCharacteristic? = null

    private var connectWaiter = CompletableDeferred<Unit>()
    private var servicesWaiter = CompletableDeferred<Unit>()
    private var mtuWaiter: CompletableDeferred<Int>? = null
    private var descriptorWaiter: CompletableDeferred<Int>? = null
    private var writeWaiter: CompletableDeferred<Int>? = null
    private val operationMutex = Mutex()

    private val rxLock = Any()
    private val rx = ByteArrayOutputStream()
    private val rxSignal = Channel<Unit>(Channel.CONFLATED)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                gatt = g
                connected = true
                connectWaiter.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                val error = IllegalStateException("BLE 已断开（status=$status）")
                if (!connectWaiter.isCompleted) connectWaiter.completeExceptionally(error)
                if (!servicesWaiter.isCompleted) servicesWaiter.completeExceptionally(error)
                mtuWaiter?.takeIf { !it.isCompleted }?.completeExceptionally(error)
                descriptorWaiter?.takeIf { !it.isCompleted }?.completeExceptionally(error)
                writeWaiter?.takeIf { !it.isCompleted }?.completeExceptionally(error)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) servicesWaiter.complete(Unit)
            else servicesWaiter.completeExceptionally(IllegalStateException("BLE 服务发现失败（$status）"))
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mtu = newMtu
                mtuWaiter?.takeIf { !it.isCompleted }?.complete(newMtu)
            } else {
                mtuWaiter?.takeIf { !it.isCompleted }?.complete(23)
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            routeNotification(c.uuid, c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            routeNotification(c.uuid, value)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            descriptorWaiter?.takeIf { !it.isCompleted }?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeWaiter?.takeIf { !it.isCompleted }?.complete(status)
        }
    }

    private fun routeNotification(uuid: UUID, bytes: ByteArray) {
        if (uuid == UUID_NOTIFY) {
            synchronized(rxLock) { rx.write(bytes) }
            rxSignal.trySend(Unit)
            if (bytes.size <= 24) log("BLE RX FF01 ${bytes.toHex()}")
        } else if (uuid == UUID_AUX) {
            // FF03 sends connection-time side-channel frames. Keep it out of the protocol receive
            // buffer so the first model/status query cannot be contaminated by those bytes.
            if (bytes.size <= 24) log("BLE RX FF03 ${bytes.toHex()}")
        }
    }

    private suspend fun attach() = withContext(Dispatchers.IO) {
        val opened = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: error("无法创建 BLE 连接")
        gatt = opened
        withTimeout(CONNECT_TIMEOUT_MS) { connectWaiter.await() }

        servicesWaiter = CompletableDeferred()
        if (!opened.discoverServices()) error("无法开始 BLE 服务发现")
        withTimeout(SERVICE_TIMEOUT_MS) { servicesWaiter.await() }

        val dataService = opened.getService(UUID_DATA_SERVICE)
            ?: error("设备没有 FF00 打印服务")
        notifyCharacteristic = dataService.getCharacteristic(UUID_NOTIFY)
            ?: error("设备没有 FF01 接收特征")
        auxCharacteristic = dataService.getCharacteristic(UUID_AUX)

        writeCharacteristic = dataService.getCharacteristic(UUID_WRITE)
            ?: opened.getService(UUID_ISSC_SERVICE)?.getCharacteristic(UUID_ISSC_WRITE)
            ?: error("设备没有 FF02/ISSC 写入特征")

        writeWithResponse = writeCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        if (!writeWithResponse && writeCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0) {
            error("BLE 写入特征不可写")
        }

        enableNotifications(opened, notifyCharacteristic, required = true)
        auxCharacteristic?.let { enableNotifications(opened, it, required = false) }

        // The tested X1 stops accepting a single ATT payload at 134 bytes. Request enough MTU for
        // 133 bytes, then cap by both the negotiated MTU and that measured printer limit.
        mtuWaiter = CompletableDeferred()
        val requested = opened.requestMtu(REQUEST_MTU)
        val negotiated = if (requested) {
            withTimeoutOrNull(MTU_TIMEOUT_MS) { mtuWaiter?.await() } ?: 23
        } else 23
        mtu = negotiated
        chunkSize = if (writeWithResponse) {
            (negotiated - ATT_OVERHEAD).coerceIn(SAFE_MIN_CHUNK, SAFE_MAX_CHUNK)
        } else {
            (negotiated - ATT_OVERHEAD).coerceIn(SAFE_MIN_CHUNK, 20)
        }
        log("BLE ready: ${device.address}, MTU=$negotiated, chunk=$chunkSize, response=$writeWithResponse")
    }

    private suspend fun enableNotifications(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        required: Boolean,
    ) {
        val localOk = g.setCharacteristicNotification(characteristic, true)
        if (!localOk && required) error("无法开启 BLE 通知")
        if (!localOk) return
        val cccd = characteristic.getDescriptor(UUID_CCCD)
        if (cccd == null) {
            if (required) error("FF01 缺少通知描述符")
            return
        }
        descriptorWaiter = CompletableDeferred()
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            }
        }
        if (!started) {
            if (required) error("无法订阅 BLE 通知")
            return
        }
        val status = withTimeoutOrNull(DESCRIPTOR_TIMEOUT_MS) { descriptorWaiter?.await() }
        if (status != BluetoothGatt.GATT_SUCCESS && required) error("BLE 通知订阅失败（$status）")
    }

    override suspend fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(bytes.size, offset + chunkSize)
            writeChunk(bytes.copyOfRange(offset, end))
            offset = end
            if (chunkDelayMs > 0) delay(chunkDelayMs)
        }
    }

    private suspend fun writeChunk(bytes: ByteArray) = operationMutex.withLock {
        val g = gatt ?: error("BLE 未连接")
        check(connected && !closed) { "BLE 未连接" }
        var last: Throwable? = null
        repeat(MAX_WRITE_RETRY) { attempt ->
            try {
                if (writeWithResponse) {
                    writeWaiter = CompletableDeferred()
                    val started = if (Build.VERSION.SDK_INT >= 33) {
                        g.writeCharacteristic(
                            writeCharacteristic,
                            bytes,
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                        ) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        run {
                            writeCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            writeCharacteristic.value = bytes
                            g.writeCharacteristic(writeCharacteristic)
                        }
                    }
                    if (!started) error("BLE 写入未启动")
                    val status = withTimeout(WRITE_TIMEOUT_MS) { writeWaiter!!.await() }
                    if (status != BluetoothGatt.GATT_SUCCESS) error("BLE 写入失败（$status）")
                } else {
                    val started = if (Build.VERSION.SDK_INT >= 33) {
                        g.writeCharacteristic(
                            writeCharacteristic,
                            bytes,
                            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                        ) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        run {
                            writeCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            writeCharacteristic.value = bytes
                            g.writeCharacteristic(writeCharacteristic)
                        }
                    }
                    if (!started) error("BLE 写入未启动")
                }
                return@withLock
            } catch (t: Throwable) {
                last = t
                delay(100L * (attempt + 1))
            }
        }
        throw last ?: IllegalStateException("BLE 写入失败")
    }

    override suspend fun query(bytes: ByteArray, firstByteTimeoutMs: Long, quietMs: Long): ByteArray {
        clearRx()
        write(bytes)
        val first = withTimeoutOrNull(firstByteTimeoutMs) { rxSignal.receive() } ?: return ByteArray(0)
        @Suppress("UNUSED_VARIABLE") val ignored = first
        while (withTimeoutOrNull(quietMs) { rxSignal.receive() } != null) {
            // Every notification extends the quiet window.
        }
        return synchronized(rxLock) { rx.toByteArray() }
    }

    private fun clearRx() {
        synchronized(rxLock) { rx.reset() }
        while (rxSignal.tryReceive().isSuccess) Unit
    }

    override fun close() {
        if (closed) return
        closed = true
        connected = false
        val g = gatt
        gatt = null
        runCatching { g?.disconnect() }
        runCatching { g?.close() }
        rxSignal.close()
    }

    companion object {
        private val UUID_DATA_SERVICE = UUID.fromString("0000FF00-0000-1000-8000-00805F9B34FB")
        private val UUID_WRITE = UUID.fromString("0000FF02-0000-1000-8000-00805F9B34FB")
        private val UUID_NOTIFY = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")
        private val UUID_AUX = UUID.fromString("0000FF03-0000-1000-8000-00805F9B34FB")
        private val UUID_ISSC_SERVICE = UUID.fromString("49535343-FE7D-4AE5-8FA9-9FAFD205E455")
        private val UUID_ISSC_WRITE = UUID.fromString("49535343-8841-43F4-A8D4-ECBE34729BB3")
        private val UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        const val SAFE_MAX_CHUNK = 133
        const val SAFE_MIN_CHUNK = 20
        private const val ATT_OVERHEAD = 3
        private const val REQUEST_MTU = SAFE_MAX_CHUNK + ATT_OVERHEAD
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val SERVICE_TIMEOUT_MS = 6_000L
        private const val MTU_TIMEOUT_MS = 2_500L
        private const val DESCRIPTOR_TIMEOUT_MS = 3_000L
        private const val WRITE_TIMEOUT_MS = 4_000L
        private const val MAX_WRITE_RETRY = 3

        suspend fun open(
            context: Context,
            device: BluetoothDevice,
            log: (String) -> Unit = {},
        ): BlePrinterConnection {
            val c = BlePrinterConnection(context.applicationContext, device, log)
            try {
                c.attach()
                return c
            } catch (t: Throwable) {
                c.close()
                throw t
            }
        }
    }
}

private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
