package io.github.soulxyz.xyprt.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import io.github.soulxyz.xyprt.printer.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Bluetooth Classic RFCOMM/SPP path used by the original X1/BY-288 application. */
class SppPrinterConnection private constructor(
    private val socket: BluetoothSocket,
    private val input: InputStream,
    private val output: OutputStream,
    override val address: String,
) : PrinterConnection {
    override val transport = PrinterTransport.CLASSIC
    override val chunkSize: Int = Protocol.SPP_CHUNK_SIZE
    override val chunkDelayMs: Long = Protocol.SPP_CHUNK_DELAY_MS
    override val isConnected: Boolean get() = socket.isConnected

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        output.write(bytes)
        output.flush()
    }

    override suspend fun query(bytes: ByteArray, firstByteTimeoutMs: Long, quietMs: Long): ByteArray =
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

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        @SuppressLint("MissingPermission")
        suspend fun open(
            context: Context,
            device: BluetoothDevice,
            log: (String) -> Unit = {},
        ): SppPrinterConnection = withContext(Dispatchers.IO) {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            runCatching { adapter?.cancelDiscovery() }
            val socket = device.createRfcommSocketToServiceRecord(UUID.fromString(Protocol.SPP_UUID))
            try {
                log("SPP connecting to ${device.address}")
                socket.connect()
                log("SPP connected")
                SppPrinterConnection(socket, socket.inputStream, socket.outputStream, device.address)
            } catch (t: Throwable) {
                runCatching { socket.close() }
                throw t
            }
        }
    }
}
