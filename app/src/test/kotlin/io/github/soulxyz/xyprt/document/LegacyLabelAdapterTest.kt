package io.github.soulxyz.xyprt.document

import io.github.soulxyz.xyprt.model.*
import io.github.soulxyz.xyprt.printer.dither.DitherMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyLabelAdapterTest {
    @Test fun `legacy template adapts and round trips without semantic loss`() {
        val original = LabelTemplate(
            id = "legacy-1", name = "兼容模板",
            spec = LabelSpec(lengthMm = 123, autoLength = false),
            elements = listOf(
                TextElement("t", 12.25f, 30.5f, text = "你好", fontSizePx = 31.5f, bold = true, fontAssetId = "font-xiaolai", boxWidthPx = 201.25f),
                FrameElement("f", 3.5f, 5.25f, widthPx = 211.5f, heightPx = 72.25f, strokePx = 2f),
                TableElement("g", 4f, 100f, rows = 4, columns = 5, widthPx = 300f, heightPx = 180f),
                BarcodeElement("b", 10f, 290f, data = "https://example.com", widthPx = 88f, heightPx = 88f),
                DrawingElement("d", 20f, 390f, strokes = listOf(DrawStroke(listOf(DrawPoint(1.25f, 2.5f), DrawPoint(9f, 20f)), 3.25f))),
                ImageElement("i", 40f, 500f, pngBase64 = "AA==", srcWidth = 20, srcHeight = 10, widthPx = 100f, dither = DitherMode.THRESHOLD),
            ),
            favorite = true, counterValue = 9, createdAt = 111L, updatedAt = 222L,
        )

        val doc = LegacyLabelAdapter.fromLegacy(original)
        assertEquals(4800, doc.page.widthMm100)
        assertEquals(12300, doc.page.heightMm100)
        assertEquals("font-xiaolai", (doc.nodes.first() as DocumentText).fontAssetId)

        val restored = LegacyLabelAdapter.toLegacy(doc)
        assertEquals(original.id, restored.id)
        assertEquals(original.name, restored.name)
        assertEquals(original.spec, restored.spec)
        assertEquals(original.favorite, restored.favorite)
        assertEquals(original.counterValue, restored.counterValue)
        assertEquals(original.createdAt, restored.createdAt)
        assertEquals(original.updatedAt, restored.updatedAt)
        assertEquals(original.elements.size, restored.elements.size)

        original.elements.zip(restored.elements).forEach { (a, b) ->
            assertEquals(a::class, b::class)
            assertEquals(a.id, b.id)
            assertTrue(kotlin.math.abs(a.x - b.x) <= 0.05f)
            assertTrue(kotlin.math.abs(a.y - b.y) <= 0.05f)
            assertEquals(a.rotation, b.rotation)
        }
        assertEquals("font-xiaolai", (restored.elements.first() as TextElement).fontAssetId)
    }
}
