package io.github.soulxyz.xyprt.ble

import java.io.IOException

/**
 * Transport failure policy for print writes.
 *
 * BluetoothSocket.isConnected only describes whether connect() succeeded; on Classic/SPP it may
 * stay true after the peer disappears and the next OutputStream.write() fails with EPIPE. Treat
 * an I/O write failure on SPP as a dead transport even when that hint still says connected.
 *
 * BLE status 13 is intentionally not classified here when GATT still reports connected: the BLE
 * transport owns its chunk-size fallback and a length rejection is not proof that the link died.
 */
internal object PrinterConnectionFailure {
    private val deadLinkHints = listOf(
        "broken pipe",
        "connection reset",
        "connection abort",
        "socket closed",
        "socket is closed",
        "transport endpoint is not connected",
        "not connected",
        "已断开",
        "未连接",
    )

    fun requiresReconnect(
        transport: PrinterTransport,
        connectedHint: Boolean,
        error: Throwable,
    ): Boolean {
        if (!connectedHint) return true
        if (transport == PrinterTransport.CLASSIC && error.causesIOException()) return true
        return error.causes.any { cause ->
            val message = cause.message?.lowercase().orEmpty()
            deadLinkHints.any(message::contains)
        }
    }

    private val Throwable.causes: Sequence<Throwable>
        get() = generateSequence(this) { it.cause }.take(8)

    private fun Throwable.causesIOException(): Boolean = causes.any { it is IOException }
}

class PrinterConnectionLostException(
    message: String,
    cause: Throwable,
) : IOException(message, cause)
