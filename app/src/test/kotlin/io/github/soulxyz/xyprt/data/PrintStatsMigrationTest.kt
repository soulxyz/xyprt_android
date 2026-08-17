package io.github.soulxyz.xyprt.data

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
}
