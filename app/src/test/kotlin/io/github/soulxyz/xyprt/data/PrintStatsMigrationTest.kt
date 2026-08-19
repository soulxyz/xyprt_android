package io.github.soulxyz.xyprt.data

import io.github.soulxyz.xyprt.model.LabelSpec
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PrintStatsMigrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `legacy history sequence preserves older print count`() {
        val raw = """
            [
              {"copies":2,"lengthMm":80,"printedAt":200},
              {"copies":1,"lengthMm":50,"printedAt":100}
            ]
        """.trimIndent()

        val recovered = recoverLegacyPrintStats(raw, historySeq = 73, json = json)

        assertEquals(73L, recovered.printCount)
        assertEquals(3L, recovered.copyCount)
        assertEquals(210L, recovered.printedLengthMm)
        assertEquals(100L, recovered.firstPrintedAt)
        assertEquals(false, recovered.mileageComplete)
    }

    @Test
    fun `new history length wins over old spec estimate`() {
        val raw = """
            [{"copies":3,"lengthMm":80,"printedLengthMm":137.5,"printedAt":1234}]
        """.trimIndent()

        val recovered = recoverLegacyPrintStats(raw, historySeq = 1, json = json)

        assertEquals(1L, recovered.printCount)
        assertEquals(3L, recovered.copyCount)
        assertEquals(138L, recovered.printedLengthMm)
        assertEquals(true, recovered.mileageComplete)
    }

    @Test
    fun `empty history still restores historical job count`() {
        val recovered = recoverLegacyPrintStats(null, historySeq = 19, json = json)
        assertEquals(19L, recovered.printCount)
        assertEquals(0L, recovered.printedLengthMm)
        assertEquals(false, recovered.mileageComplete)
    }

    @Test
    fun `legacy max history id recovers count when sequence is missing`() {
        val raw = """
            [
              {"id": 41, "copies": 1, "lengthMm": 80, "printedAt": 1700000000000}
            ]
        """.trimIndent()

        val recovered = recoverLegacyPrintStats(raw, historySeq = 0, json = json)

        assertEquals(41L, recovered.printCount)
        assertFalse(recovered.mileageComplete)
    }

    @Test
    fun `merge stats accumulates counts and keeps earliest first print`() {
        val current = PrintStatsSnapshot(printCount = 5, copyCount = 8, printedLengthMm = 400, firstPrintedAt = 2000, mileageComplete = true)
        val incoming = PrintStatsSnapshot(printCount = 3, copyCount = 4, printedLengthMm = 137, firstPrintedAt = 1000, mileageComplete = true)

        val merged = mergeStats(current, incoming)

        assertEquals(8L, merged.printCount)
        assertEquals(12L, merged.copyCount)
        assertEquals(537L, merged.printedLengthMm)
        assertEquals(1000L, merged.firstPrintedAt)
        assertEquals(true, merged.mileageComplete)
    }

    @Test
    fun `merge stats keeps current first print when incoming has none`() {
        val current = PrintStatsSnapshot(printCount = 2, copyCount = 2, printedLengthMm = 100, firstPrintedAt = 3000)
        val merged = mergeStats(current, PrintStatsSnapshot(printCount = 1, copyCount = 1, printedLengthMm = 50))
        assertEquals(3000L, merged.firstPrintedAt)
    }

    @Test
    fun `merge stats mileage complete requires both sides complete`() {
        val merged = mergeStats(
            PrintStatsSnapshot(printCount = 2, mileageComplete = true),
            PrintStatsSnapshot(printCount = 1, mileageComplete = false),
        )
        assertFalse(merged.mileageComplete)
    }

    @Test
    fun `stats recovered from imported history use explicit printed length`() {
        val entries = listOf(
            PrintHistoryEntry(
                id = 0, templateId = null, templateName = "a", spec = LabelSpec(),
                elements = emptyList(), copies = 3, printedAt = 500, printedLengthMm = 137.5,
            ),
            PrintHistoryEntry(
                id = 0, templateId = null, templateName = "b", spec = LabelSpec(),
                elements = emptyList(), copies = 1, printedAt = 100, printedLengthMm = null,
            ),
        )

        val recovered = recoverStatsFromEntries(entries)

        assertEquals(2L, recovered.printCount)
        assertEquals(4L, recovered.copyCount)
        assertEquals(218L, recovered.printedLengthMm)
        assertEquals(100L, recovered.firstPrintedAt)
        assertEquals(true, recovered.mileageComplete)
    }

    @Test
    fun `subtract stats removes duplicate contribution and clamps at zero`() {
        val base = PrintStatsSnapshot(printCount = 10, copyCount = 14, printedLengthMm = 900, firstPrintedAt = 500, mileageComplete = false)
        val dup = PrintStatsSnapshot(printCount = 3, copyCount = 4, printedLengthMm = 137, firstPrintedAt = 100)

        val adjusted = subtractStats(base, dup)

        assertEquals(7L, adjusted.printCount)
        assertEquals(10L, adjusted.copyCount)
        assertEquals(763L, adjusted.printedLengthMm)
        assertEquals(500L, adjusted.firstPrintedAt)
        assertEquals(false, adjusted.mileageComplete)
        assertEquals(0L, subtractStats(PrintStatsSnapshot(printCount = 1), PrintStatsSnapshot(printCount = 5)).printCount)
    }
}
