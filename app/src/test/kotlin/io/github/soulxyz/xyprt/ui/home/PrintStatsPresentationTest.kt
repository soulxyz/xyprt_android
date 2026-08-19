package io.github.soulxyz.xyprt.ui.home

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `short lengths compare to everyday small objects`() {
        val candidates = printedDistanceCandidates(180)
        assertTrue(candidates.any { it.contains("铅笔") })
        assertTrue(candidates.size > 1)
    }

    @Test
    fun `medium lengths offer several plausible comparisons`() {
        val candidates = printedDistanceCandidates(1_500)
        assertTrue(candidates.any { it.contains("双人床") })
        assertTrue(candidates.size > 2)
    }

    @Test
    fun `long distances use familiar landmarks`() {
        assertTrue(printedDistanceCandidates(42_195_000).any { it.contains("马拉松") })
        assertTrue(printedDistanceCandidates(1_318_000_000).any { it.contains("京沪高铁") })
        assertTrue(printedDistanceCandidates(384_400_000_000L).any { it.contains("月球") })
    }

    @Test
    fun `very long distances always fall back to earth laps`() {
        val candidates = printedDistanceCandidates(10_000_000_000_000L)
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.all { it.contains("绕地球") })
    }

    @Test
    fun `random pick is stable for the same seed`() {
        val a = printedDistanceAnalogy(180, Random(0))
        val b = printedDistanceAnalogy(180, Random(0))
        assertNotNull(a)
        assertEquals(a, b)
        assertTrue(printedDistanceCandidates(180).contains(a))
    }

    @Test
    fun `no analogy for trivially short lengths`() {
        assertNull(printedDistanceAnalogy(5, Random(0)))
    }
}
