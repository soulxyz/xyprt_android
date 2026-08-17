package io.github.soulxyz.xyprt.scanner

/**
 * Safety gates that deliberately prefer an honest manual frame over a confident destructive crop.
 * Kept pure so the fallback contract stays unit-testable without Android/OpenCV instrumentation.
 */
internal object DocumentScanPolicy {
    fun shouldUseManualFallback(score: Double?, area: Float?): Boolean =
        score == null || area == null || (score < .15 && area < .15f)

    fun confidenceFor(score: Double?, manualFallback: Boolean): Float = when {
        manualFallback || score == null -> .25f
        score >= .34 -> .92f
        score >= .24 -> .82f
        score >= .15 -> .70f
        else -> .56f
    }
}
