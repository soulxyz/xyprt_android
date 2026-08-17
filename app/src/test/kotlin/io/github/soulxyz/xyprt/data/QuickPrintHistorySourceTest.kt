package io.github.soulxyz.xyprt.data

import org.junit.Test
import org.junit.Assert.assertEquals
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class QuickPrintHistorySourceTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun quickPrintSourceRoundTripsEditingState() {
        val src = QuickPrintHistorySource(
            mode = "CAMERA",
            uris = listOf("content://xyprt/camera/1"),
            ditherMode = "THRESHOLD",
            paperPreset = "DOCUMENT",
            threshold = 188,
            rotationDegrees = 90,
            scalePercent = 115,
            cameraQuad = listOf(.1f,.1f,.9f,.1f,.9f,.9f,.1f,.9f),
        )
        val decoded = json.decodeFromString<QuickPrintHistorySource>(json.encodeToString(src))
        assertEquals("CAMERA", decoded.mode)
        assertEquals(90, decoded.rotationDegrees)
        assertEquals("DOCUMENT", decoded.paperPreset)
        assertEquals(src.cameraQuad, decoded.cameraQuad)
    }

    @Test
    fun importedImageKeepsDocumentPresetAndQuad() {
        val src = QuickPrintHistorySource(
            mode = "IMAGE",
            uris = listOf("content://xyprt/import/1"),
            paperPreset = "GRAYSCALE",
            cameraQuad = listOf(.05f,.08f,.94f,.06f,.96f,.92f,.04f,.95f),
        )
        val decoded = json.decodeFromString<QuickPrintHistorySource>(json.encodeToString(src))
        assertEquals("IMAGE", decoded.mode)
        assertEquals("GRAYSCALE", decoded.paperPreset)
        assertEquals(src.cameraQuad, decoded.cameraQuad)
    }
}
