package io.github.toolicious.labler.printer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterRasterTest {
    @Test
    fun `packer emits 48 bytes per portrait row MSB leftmost`() {
        val image = MonoImage.blank(2)
        image.setBlack(0, 0)
        image.setBlack(7, 0)
        image.setBlack(8, 0)
        image.setBlack(383, 1)

        val packed = ColumnPacker.packColumns(image)
        assertEquals(96, packed.size)
        assertEquals(0x81, packed[0].toInt() and 0xff)
        assertEquals(0x80, packed[1].toInt() and 0xff)
        assertEquals(0x01, packed[95].toInt() and 0xff)
    }

    @Test
    fun `continuous trim removes only trailing blank paper`() {
        val image = MonoImage.blank(200)
        image.setBlack(10, 50)
        image.setBlack(20, 75)
        val trimmed = image.trimTrailingWhite(bottomMarginDots = 24, minimumHeightDots = 64)
        assertEquals(100, trimmed.height)
        assertTrue(trimmed.isBlack(10, 50))
        assertTrue(trimmed.isBlack(20, 75))
        assertFalse(trimmed.isBlack(0, 99))
    }
}
