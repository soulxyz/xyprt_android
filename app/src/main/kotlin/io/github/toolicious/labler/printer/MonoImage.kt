package io.github.toolicious.labler.printer

/**
 * 1-bit image in normal portrait paper coordinates.
 *
 * x = across the print head (0..383), y = feed direction / document length.
 * true = black. This matches how users see a receipt/document on screen: fixed
 * paper width, variable vertical length.
 */
class MonoImage(val height: Int, val black: BooleanArray) {

    val width: Int get() = Protocol.HEAD_DOTS

    init {
        require(height in 1..0xFFFF) { "Document height must be 1..65535 dots, was $height" }
        require(black.size == width * height) {
            "Pixel buffer does not fit: ${black.size} instead of ${width * height}"
        }
    }

    fun isBlack(x: Int, y: Int): Boolean = black[y * width + x]

    fun setBlack(x: Int, y: Int) {
        if (x in 0 until width && y in 0 until height) black[y * width + x] = true
    }

    /** Crops only trailing blank paper; content width always stays 384 dots. */
    fun trimTrailingWhite(bottomMarginDots: Int = 24, minimumHeightDots: Int = 64): MonoImage {
        var last = -1
        loop@ for (y in height - 1 downTo 0) {
            val off = y * width
            for (x in 0 until width) {
                if (black[off + x]) { last = y; break@loop }
            }
        }
        val wanted = if (last < 0) minimumHeightDots else last + 1 + bottomMarginDots
        val h = wanted.coerceIn(minimumHeightDots, height)
        if (h == height) return this
        return MonoImage(h, black.copyOf(h * width))
    }

    companion object {
        fun blank(height: Int): MonoImage = MonoImage(height, BooleanArray(height * Protocol.HEAD_DOTS))
    }
}
