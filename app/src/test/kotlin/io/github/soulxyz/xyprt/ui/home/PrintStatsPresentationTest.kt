package io.github.soulxyz.xyprt.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrintStatsPresentationTest {
    @Test
    fun `distance uses familiar units and keeps incomplete history honest`() {
        assertEquals("18 cm", formatPrintedDistance(180, complete = true))
        assertEquals("至少 18 cm", formatPrintedDistance(180, complete = false))
        assertEquals("1.25 m", formatPrintedDistance(1250, complete = true))
        assertEquals("1.2 km", formatPrintedDistance(1_200_000, complete = true))
    }

    @Test
    fun `analogy only appears when comparison is natural`() {
        assertEquals("大约一支铅笔的长度", printedDistanceAnalogy(180))
        assertEquals("差不多绕标准跑道一圈", printedDistanceAnalogy(400_000))
        assertNull(printedDistanceAnalogy(5_000))
    }
}
