package io.github.toolicious.labler.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import io.github.toolicious.labler.printer.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Bluetooth Classic RFCOMM/SPP connection used by Beeprt BY-288. */
class PrinterConnection private constructor(
    private val socket: BluetoothSocket,
    private val input: InputStream,
    private val output: OutputStream,
    val chunkSize: Int,
) {
    val isConnected: Boolean get() = socket.isConnected

    suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        output.write(bytes)
        output.flush()
    }

    /** Write a query and collect bytes until a short quiet period follows the first response. */
    suspend fun query(bytes: ByteArray, firstByteTimeoutMs: Long = 1000, quietMs: Long = 120): ByteArray =
        withContext(Dispatchers.IO) {
            output.write(bytes)
            output.flush()
            val out = ByteArrayOutputStream()
            val firstDeadline = System.currentTimeMillis() + firstByteTimeoutMs
            while (input.available() <= 0 && System.currentTimeMillis() < firstDeadline) {
                Thread.sleep(10)
            }
            var quietDeadline = System.currentTimeMillis() + quietMs
            while (System.currentTimeMillis() < quietDeadline) {
                val available = input.available()
                if (available > 0) {
                    val buf = ByteArray(minOf(512, available))
                    val n = input.read(buf)
                    if (n > 0) {
                        out.write(buf, 0, n)
                        quietDeadline = System.currentTimeMillis() + quietMs
                    }
                } else {
                    Thread.sleep(10)
                }
            }
            out.toByteArray()
        }

    fun close() = runCatching { socket.close() }.getOrNull()

    companion object {
        @SuppressLint("MissingPermission")
        suspend fun open(
            context: Context,
            device: BluetoothDevice,
            autoConnect: Boolean = false,
            connectTimeoutMs: Long? = 10_000,
            log: (String) -> Unit = {},
        ): PrinterConnection = withContext(Dispatchers.IO) {
            // SPP has no GATT autoConnect equivalent; the arguments remain for source/API compatibility.
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            runCatching { adapter?.cancelDiscovery() }
            val socket = device.createRfcommSocketToServiceRecord(UUID.fromString(Protocol.SPP_UUID))
            try {
                log("RFCOMM connecting to ${device.address} ...")
                socket.connect()
                log("RFCOMM connected")
                PrinterConnection(socket, socket.inputStream, socket.outputStream, Protocol.CHUNK_SIZE)
            } catch (t: Throwable) {
                runCatching { socket.close() }
                throw t
            }
        }
    }
}
