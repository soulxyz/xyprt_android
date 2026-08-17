package io.github.soulxyz.xyprt.ui.history

import io.github.soulxyz.xyprt.data.QuickPrintHistorySource
import io.github.soulxyz.xyprt.model.FrameElement
import io.github.soulxyz.xyprt.model.LabelFont
import io.github.soulxyz.xyprt.model.LabelTextAlign
import io.github.soulxyz.xyprt.model.TextElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickHistoryLayoutConverterTest {
    @Test
    fun `quick text stays editable in free layout`() {
        val layout = QuickHistoryLayoutConverter.convert(
            QuickPrintHistorySource(
                mode = "TEXT",
                text = "第一行\n第二行",
                fontSizePx = 36,
                lineSpacingPercent = 135,
                font = "SERIF",
                align = "CENTER",
            ),
        ) ?: error("missing layout")

        assertEquals(1, layout.elements.size)
        val text = layout.elements.single() as TextElement
        assertEquals("第一行\n第二行", text.text)
        assertEquals(36f, text.fontSizePx)
        assertEquals(135, text.lineSpacingPercent)
        assertEquals(LabelFont.SERIF, text.font)
        assertEquals(LabelTextAlign.CENTER, text.align)
        assertTrue(text.boxWidthPx != null)
    }

    @Test
    fun `todo becomes editable structured layout`() {
        val layout = QuickHistoryLayoutConverter.convert(
            QuickPrintHistorySource(mode = "TODO", todoTitle = "今天", todoItems = "买纸\n打印错题"),
        ) ?: error("missing layout")

        assertTrue(layout.elements.first() is TextElement)
        // One header divider plus two real checkbox frames. Nothing is flattened into an image.
        assertEquals(3, layout.elements.filterIsInstance<FrameElement>().size)
        assertEquals(listOf("今天", "买纸", "打印错题"), layout.elements.filterIsInstance<TextElement>().map { it.text })
        assertEquals(LabelTextAlign.CENTER, (layout.elements.first() as TextElement).align)
    }

    @Test
    fun `todo keeps date and title alignment when reopened`() {
        val layout = QuickHistoryLayoutConverter.convert(
            QuickPrintHistorySource(
                mode = "TODO",
                todoTitle = "周末清单",
                todoItems = "买菜",
                todoShowDate = true,
                todoDate = "8月17日 周一",
                todoCenterTitle = false,
                todoPreset = "FOCUS",
            ),
        ) ?: error("missing layout")

        val texts = layout.elements.filterIsInstance<TextElement>()
        assertEquals(listOf("周末清单", "8月17日 周一", "买菜"), texts.map { it.text })
        assertEquals(LabelTextAlign.LEFT, texts[0].align)
        assertEquals(LabelTextAlign.LEFT, texts[1].align)
        assertTrue(layout.elements.filterIsInstance<FrameElement>().size >= 2)
    }
}
