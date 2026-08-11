package io.github.soulxyz.xyprt.data

import io.github.soulxyz.xyprt.model.LabelSpec
import io.github.soulxyz.xyprt.printer.MediaType
import io.github.soulxyz.xyprt.printer.MonoImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRasterTest {
    @Test
    fun exactRasterRoundTripPreservesBits() {
        val image = MonoImage.blank(137)
        image.setBlack(0, 0)
        image.setBlack(383, 0)
        image.setBlack(17, 22)
        image.setBlack(200, 136)

        val encoded = HistoryRepository.encodeRaster(image)
        val entry = PrintHistoryEntry(
            id = 1,
            templateId = null,
            templateName = "快速图片",
            spec = LabelSpec(lengthMm = 18, media = MediaType.CONTINUOUS),
            elements = emptyList(),
            copies = 1,
            printedAt = 0,
            rasterBase64 = encoded,
            rasterHeight = image.height,
        )
        val decoded = HistoryRepository.decodeRaster(entry)
        assertNotNull(decoded)
        decoded!!
        assertEquals(137, decoded.height)
        assertTrue(decoded.isBlack(0, 0))
        assertTrue(decoded.isBlack(383, 0))
        assertTrue(decoded.isBlack(17, 22))
        assertTrue(decoded.isBlack(200, 136))
        assertFalse(decoded.isBlack(18, 22))
    }
}
