package io.github.soulxyz.xyprt.ui.quickprint

import io.github.soulxyz.xyprt.scanner.DocumentQuad
import io.github.soulxyz.xyprt.scanner.QuadPoint
import io.github.soulxyz.xyprt.scanner.ScanDetection
import io.github.soulxyz.xyprt.scanner.ScanEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageFlowPolicyTest {
    @Test fun photographed_sheet_is_suggested_without_forcing_crop() {
        val quad = DocumentQuad(
            QuadPoint(.12f, .08f), QuadPoint(.90f, .12f),
            QuadPoint(.86f, .91f), QuadPoint(.15f, .88f),
        )
        assertTrue(shouldSuggestDocumentCorrection(ScanDetection(quad, ScanEngine.STANDARD, .82f)))
    }

    @Test fun full_frame_artwork_is_not_treated_as_document() {
        assertFalse(shouldSuggestDocumentCorrection(ScanDetection(fullImageQuad(), ScanEngine.STANDARD, .92f)))
    }

    @Test fun weak_detection_never_interrupts_image_flow() {
        val quad = DocumentQuad(
            QuadPoint(.15f, .15f), QuadPoint(.85f, .15f),
            QuadPoint(.85f, .85f), QuadPoint(.15f, .85f),
        )
        assertFalse(shouldSuggestDocumentCorrection(ScanDetection(quad, ScanEngine.STANDARD, .56f)))
    }
}
