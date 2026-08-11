package io.github.soulxyz.xyprt.ui.quickprint

data class CropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    fun normalized(minSize: Float = 0.05f): CropRect {
        var l = left.coerceIn(0f, 1f)
        var t = top.coerceIn(0f, 1f)
        var r = right.coerceIn(0f, 1f)
        var b = bottom.coerceIn(0f, 1f)
        if (r < l) { val x = l; l = r; r = x }
        if (b < t) { val y = t; t = b; b = y }
        if (r - l < minSize) r = (l + minSize).coerceAtMost(1f)
        if (b - t < minSize) b = (t + minSize).coerceAtMost(1f)
        if (r - l < minSize) l = (r - minSize).coerceAtLeast(0f)
        if (b - t < minSize) t = (b - minSize).coerceAtLeast(0f)
        return CropRect(l, t, r, b)
    }

    val isFull: Boolean get() = left <= 0.001f && top <= 0.001f && right >= 0.999f && bottom >= 0.999f
}
