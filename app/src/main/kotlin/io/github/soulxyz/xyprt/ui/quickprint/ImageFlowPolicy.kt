package io.github.soulxyz.xyprt.ui.quickprint

import io.github.soulxyz.xyprt.scanner.DocumentQuad
import io.github.soulxyz.xyprt.scanner.ScanDetection

/**
 * Product policy for gallery images: never interrupt the user with document cropping just because
 * an image contains a rectangle. We may show a quiet correction suggestion when the detector has
 * strong evidence of a photographed sheet and the quad is meaningfully different from full image.
 */
internal fun shouldSuggestDocumentCorrection(detection: ScanDetection): Boolean {
    val q = detection.quad
    return detection.confidence >= 0.70f &&
        q.isReasonable() &&
        q.area in 0.08f..0.94f &&
        !q.isEffectivelyFullImage()
}

internal fun DocumentQuad.isEffectivelyFullImage(): Boolean {
    val full = listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f)
    return points().zip(full).all { (p, t) ->
        kotlin.math.abs(p.x - t.first) <= .07f && kotlin.math.abs(p.y - t.second) <= .07f
    }
}

internal fun fullImageQuad() = DocumentQuad(
    topLeft = io.github.soulxyz.xyprt.scanner.QuadPoint(0f, 0f),
    topRight = io.github.soulxyz.xyprt.scanner.QuadPoint(1f, 0f),
    bottomRight = io.github.soulxyz.xyprt.scanner.QuadPoint(1f, 1f),
    bottomLeft = io.github.soulxyz.xyprt.scanner.QuadPoint(0f, 1f),
)
