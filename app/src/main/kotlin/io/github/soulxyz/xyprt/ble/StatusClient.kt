package io.github.soulxyz.xyprt.ble

import io.github.soulxyz.xyprt.printer.Protocol
import kotlinx.coroutines.delay

data class PrinterInfo(
    val model: String?,
    val firmware: String?,
    val serial: String?,
    val hardware: String?,
)

/** Best-effort BY-288 status client over the same RFCOMM input/output stream. */
class StatusClient(private val connection: PrinterConnection) {
    suspend fun initialize(): Boolean = true

    suspend fun batteryPercent(): Int? {
        val r = query(Protocol.QUERY_BATTERY) ?: return null
        if (r.isEmpty()) return null
        // BYPrintPort responses vary by firmware; common form keeps the percentage in byte 1.
        val candidates = buildList {
            if (r.size >= 2) add(r[1].toInt() and 0xFF)
            add(r.last().toInt() and 0xFF)
            add(r.first().toInt() and 0xFF)
        }
        return candidates.firstOrNull { it in 0..100 }
    }

    suspend fun printerInfo(): PrinterInfo = PrinterInfo(
        model = queryText(Protocol.QUERY_MODEL),
        firmware = queryText(Protocol.QUERY_FIRMWARE),
        serial = queryText(Protocol.QUERY_SERIAL),
        hardware = queryText(Protocol.QUERY_HARDWARE),
    )

    private suspend fun queryText(cmd: ByteArray): String? = query(cmd)?.let { bytes ->
        val text = runCatching { bytes.toString(java.nio.charset.Charset.forName("GB18030")) }
            .getOrElse { bytes.toString(Charsets.UTF_8) }
            .replace("\u0000", "")
            .trim()
        text.takeIf { it.isNotEmpty() }
    }

    private suspend fun query(cmd: ByteArray): ByteArray? {
        val r = runCatching { connection.query(cmd) }.getOrNull()?.takeIf { it.isNotEmpty() }
        delay(Protocol.QUERY_GAP_MS)
        return r
    }
}
