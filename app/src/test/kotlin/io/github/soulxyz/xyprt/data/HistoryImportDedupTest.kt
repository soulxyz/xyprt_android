package io.github.soulxyz.xyprt.data

import io.github.soulxyz.xyprt.model.LabelSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryImportDedupTest {

    private fun entry(
        id: Long = 0L,
        name: String = "t",
        copies: Int = 1,
        lengthMm: Int = 80,
        printedAt: Long = 1000,
    ) = PrintHistoryEntry(
        id = id,
        templateId = null,
        templateName = name,
        spec = LabelSpec(lengthMm = lengthMm),
        elements = emptyList(),
        copies = copies,
        printedAt = printedAt,
    )

    @Test
    fun `duplicate by time is skipped on merge import`() {
        val existing = listOf(entry(id = 3, name = "标签A", copies = 2, printedAt = 100))
        val incoming = listOf(
            entry(id = 5, name = "标签A", copies = 2, printedAt = 100),
            entry(id = 6, name = "标签B", copies = 1, printedAt = 200),
        )

        val (fresh, skipped) = partitionDuplicateHistory(incoming, existing)

        assertEquals(listOf(200L), fresh.map { it.printedAt })
        assertEquals(listOf(100L), skipped.map { it.printedAt })
    }

    @Test
    fun `duplicate by original id is skipped even when time differs`() {
        val existing = listOf(entry(id = 7, name = "标签A", printedAt = 100))
        val incoming = listOf(entry(id = 7, name = "标签A", printedAt = 999))

        val (fresh, skipped) = partitionDuplicateHistory(incoming, existing)

        assertEquals(0, fresh.size)
        assertEquals(1, skipped.size)
    }

    @Test
    fun `duplicates inside the incoming batch itself collapse`() {
        val same = entry(id = 0, name = "标签A", copies = 1, printedAt = 100)
        val incoming = listOf(same, same.copy(), entry(name = "标签B", printedAt = 200))

        val (fresh, skipped) = partitionDuplicateHistory(incoming, emptyList())

        assertEquals(2, fresh.size)
        assertEquals(1, skipped.size)
    }

    @Test
    fun `different specs or names at the same time stay distinct`() {
        val existing = listOf(entry(id = 1, name = "标签A", lengthMm = 80, printedAt = 100))
        val incoming = listOf(entry(id = 0, name = "标签A", lengthMm = 120, printedAt = 100))

        val (fresh, skipped) = partitionDuplicateHistory(incoming, existing)

        assertEquals(1, fresh.size)
        assertEquals(0, skipped.size)
    }
}
