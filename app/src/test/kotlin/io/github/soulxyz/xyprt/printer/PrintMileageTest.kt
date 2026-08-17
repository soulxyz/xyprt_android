package io.github.soulxyz.xyprt.printer

import org.junit.Assert.assertEquals
import org.junit.Test

class PrintMileageTest {
    @Test
    fun `continuous mileage includes feeds and all copies`() {
        val image = MonoImage.blank(80).also { it.setBlack(0, 79) }
        val mm = estimatePrintedLengthMm(
            image = image,
            media = MediaType.CONTINUOUS,
            copies = 3,
            feedBeforeDots = 8,
            feedAfterDots = 16,
        )
        assertEquals(39.0, mm, 0.0001)
    }

    @Test
    fun `die cut mileage does not invent unknown form feed distance`() {
        val image = MonoImage.blank(80)
        val mm = estimatePrintedLengthMm(
            image = image,
            media = MediaType.DIE_CUT,
            copies = 2,
            feedBeforeDots = 200,
            feedAfterDots = 200,
        )
        assertEquals(20.0, mm, 0.0001)
    }
}
