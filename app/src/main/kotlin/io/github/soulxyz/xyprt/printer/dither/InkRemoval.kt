package io.github.soulxyz.xyprt.printer.dither

/**
 * Simple local color suppression for photographed worksheets. It intentionally targets only
 * strongly chromatic red/blue pixels so black printed text and pencil marks remain untouched.
 */
object InkRemoval {
    fun apply(argb: IntArray, removeRed: Boolean, removeBlue: Boolean): IntArray {
        if (!removeRed && !removeBlue) return argb
        return IntArray(argb.size) { i ->
            val p = argb[i]
            val a = p ushr 24
            if (a < 16) return@IntArray p
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            val redInk = removeRed && r >= 105 && r - g >= 32 && r - b >= 32
            val blueInk = removeBlue && b >= 85 && b - r >= 24 && b - g >= 18
            if (redInk || blueInk) (a shl 24) or 0x00ffffff else p
        }
    }
}
