package io.github.toolicious.labler.printer

/** Internal portrait geometry pattern. Not shown in the normal alpha2 UI. */
object TestPattern {
    fun create(lengthDots: Int = 320): MonoImage {
        val img = MonoImage.blank(lengthDots)
        val w = Protocol.HEAD_DOTS
        val h = lengthDots
        for (x in 0 until w) { img.setBlack(x, 0); img.setBlack(x, h - 1) }
        for (y in 0 until h) { img.setBlack(0, y); img.setBlack(w - 1, y) }
        val dmax = minOf(w, h)
        for (d in 0 until dmax) img.setBlack(d, d)
        for (x in 12 until 36) for (y in 12 until minOf(36, h)) img.setBlack(x, y)
        return img
    }
}
