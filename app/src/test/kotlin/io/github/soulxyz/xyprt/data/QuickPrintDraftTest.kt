package io.github.soulxyz.xyprt.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickPrintDraftTest {
    @Test fun draftRoundTripKeepsEditingState() {
        val json = Json { ignoreUnknownKeys = true }
        val draft = QuickPrintDraft(
            source = QuickPrintHistorySource(
                mode = "IMAGE",
                uris = listOf("content://example/image"),
                paperPreset = "DOCUMENT",
                rotationDegrees = 90,
                landscapePrint = true,
                cameraQuad = listOf(.1f,.1f,.9f,.1f,.9f,.9f,.1f,.9f),
            ),
            showCropEditor = true,
            imageCorrectionApplied = false,
        )
        val decoded = json.decodeFromString<QuickPrintDraft>(json.encodeToString(QuickPrintDraft.serializer(), draft))
        assertTrue(decoded.showCropEditor)
        assertEquals(true, decoded.source.landscapePrint)
        assertEquals(8, decoded.source.cameraQuad.size)
    }
}
