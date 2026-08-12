package io.github.soulxyz.xyprt.ble

import io.github.soulxyz.xyprt.printer.Chunker
import io.github.soulxyz.xyprt.printer.Protocol
import kotlinx.coroutines.delay

object PrintJobSender {
    suspend fun sendAll(
        connection: PrinterConnection,
        payloads: List<ByteArray>,
        onProgress: (Float, Int) -> Unit = { _, _ -> },
    ) {
        require(payloads.isNotEmpty()) { "At least one print job" }
        val totalBytes = payloads.sumOf { it.size }.toLong().coerceAtLeast(1)
        var sent = 0L
        payloads.forEachIndexed { index, payload ->
            if (index > 0) delay(Protocol.COPY_DELAY_MS)
            for (chunk in Chunker.chunk(payload, connection.chunkSize)) {
                connection.write(chunk)
                if (connection.chunkDelayMs > 0) delay(connection.chunkDelayMs)
                sent += chunk.size
                onProgress(sent.toFloat() / totalBytes, index + 1)
            }
        }
    }
}
