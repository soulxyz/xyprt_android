package io.github.soulxyz.xyprt.printer

import java.io.ByteArrayOutputStream

enum class MediaType { DIE_CUT, CONTINUOUS }

/** Builds one complete BY-288 print job from a normal portrait 384-dot-wide image. */
object PrintJobBuilder {
    fun buildJob(source: MonoImage, media: MediaType): ByteArray {
        // Continuous paper normally should not force users to guess a page length. Trim only
        // trailing white area and leave a small bottom margin. Die-cut keeps the requested size.
        val image = if (media == MediaType.CONTINUOUS) source.trimTrailingWhite() else source
        val payload = ColumnPacker.packColumns(image)
        val out = ByteArrayOutputStream(payload.size + 64)

        out.write(Protocol.PRINT_START)
        out.write(Protocol.PRINT_ENABLE_2)
        out.write(Protocol.density(Protocol.DEFAULT_DENSITY))
        out.write(Protocol.WAKEUP)
        out.write(Protocol.feedDots(Protocol.PRE_FEED_DOTS))

        // GS v 0: x = 48 bytes = 384 dots across paper; y = rows in feed direction.
        out.write(Protocol.RASTER_GS_V0)
        out.write(Protocol.BYTES_PER_COLUMN and 0xFF)
        out.write((Protocol.BYTES_PER_COLUMN shr 8) and 0xFF)
        out.write(image.height and 0xFF)
        out.write((image.height shr 8) and 0xFF)
        out.write(payload)

        when (media) {
            MediaType.DIE_CUT -> out.write(Protocol.FORM_FEED)
            MediaType.CONTINUOUS -> out.write(Protocol.feedDots(Protocol.CONTINUOUS_FEED_DOTS))
        }
        out.write(Protocol.PRINT_END)
        return out.toByteArray()
    }
}
