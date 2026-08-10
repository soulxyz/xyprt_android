package io.github.toolicious.labler.printer

import java.io.ByteArrayOutputStream

/** Paper type: die-cut labels with a gap or continuous thermal paper. */
enum class MediaType { DIE_CUT, CONTINUOUS }

/** Builds one complete BY-288 print job from the editor's 1-bit image. */
object PrintJobBuilder {
    fun buildJob(image: MonoImage, media: MediaType): ByteArray {
        val payload = ColumnPacker.packColumns(image)
        val out = ByteArrayOutputStream(payload.size + 64)

        // Original 错题小印/BYPrintPort job preamble.
        out.write(Protocol.PRINT_START)
        out.write(Protocol.PRINT_ENABLE_2)
        out.write(Protocol.density(Protocol.DEFAULT_DENSITY))
        out.write(Protocol.WAKEUP)
        out.write(Protocol.feedDots(Protocol.PRE_FEED_DOTS))

        // GS v 0, x = 48 bytes = 384 dots; y = label length in dot rows.
        out.write(Protocol.RASTER_GS_V0)
        out.write(Protocol.BYTES_PER_COLUMN and 0xFF)
        out.write((Protocol.BYTES_PER_COLUMN shr 8) and 0xFF)
        out.write(image.width and 0xFF)
        out.write((image.width shr 8) and 0xFF)
        out.write(payload)

        when (media) {
            // Position command is known from the original SDK. Keep it only for explicit die-cut mode.
            MediaType.DIE_CUT -> out.write(Protocol.FORM_FEED)
            MediaType.CONTINUOUS -> out.write(Protocol.feedDots(Protocol.CONTINUOUS_FEED_DOTS))
        }
        out.write(Protocol.PRINT_END)
        return out.toByteArray()
    }
}
