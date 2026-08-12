package io.github.soulxyz.xyprt.ble

/**
 * Byte transport used by the X1 printer protocol.
 *
 * The printer protocol itself is identical on the currently known Classic-SPP and BLE
 * hardware revisions. Keeping transport behind this interface lets the normal UI stay
 * completely unaware of which radio path a particular printer uses.
 */
interface PrinterConnection {
    val transport: PrinterTransport
    val address: String
    val chunkSize: Int
    val chunkDelayMs: Long
    val isConnected: Boolean

    suspend fun write(bytes: ByteArray)

    /** Write a query and collect bytes until a short quiet period follows the first response. */
    suspend fun query(
        bytes: ByteArray,
        firstByteTimeoutMs: Long = 1_000,
        quietMs: Long = 120,
    ): ByteArray

    fun close()
}

enum class PrinterTransport(val savedValue: String, val shortLabel: String) {
    CLASSIC("classic", "蓝牙"),
    BLE("ble", "BLE");

    companion object {
        fun fromSaved(value: String?): PrinterTransport? = entries.firstOrNull { it.savedValue == value }
    }
}
