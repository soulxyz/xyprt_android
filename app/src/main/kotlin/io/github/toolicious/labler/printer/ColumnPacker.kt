package io.github.toolicious.labler.printer

/**
 * Historical file name retained to minimize call-site churn. This build packs normal
 * GS-v-0 raster rows: 48 bytes per feed row, MSB = left-most pixel.
 */
object ColumnPacker {
    fun packColumns(image: MonoImage): ByteArray {
        val out = ByteArray(image.height * Protocol.BYTES_PER_COLUMN)
        var i = 0
        for (y in 0 until image.height) {
            var x = 0
            while (x < Protocol.HEAD_DOTS) {
                var b = 0
                for (bit in 0 until 8) {
                    if (image.isBlack(x + bit, y)) b = b or (0x80 ushr bit)
                }
                out[i++] = b.toByte()
                x += 8
            }
        }
        return out
    }
}
