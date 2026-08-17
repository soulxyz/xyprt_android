package io.github.soulxyz.xyprt.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentScanPolicyTest {
    @Test fun missingCandidateAlwaysFallsBack() {
        assertTrue(DocumentScanPolicy.shouldUseManualFallback(null, null))
        assertEquals(.25f, DocumentScanPolicy.confidenceFor(null, true), 0f)
    }

    @Test fun tinyWeakCandidateCannotDestructivelyCropMostOfPhoto() {
        assertTrue(DocumentScanPolicy.shouldUseManualFallback(.116, .056f))
    }

    @Test fun smallButCredibleNoteCanStillBeDetected() {
        assertFalse(DocumentScanPolicy.shouldUseManualFallback(.194, .038f))
        assertEquals(.70f, DocumentScanPolicy.confidenceFor(.194, false), 0f)
    }

    @Test fun strongPageCandidateStaysAutomatic() {
        assertFalse(DocumentScanPolicy.shouldUseManualFallback(.43, .30f))
        assertEquals(.92f, DocumentScanPolicy.confidenceFor(.43, false), 0f)
    }
}
