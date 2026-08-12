package io.github.soulxyz.xyprt.data

import io.github.soulxyz.xyprt.model.DrawPoint
import io.github.soulxyz.xyprt.model.DrawStroke
import io.github.soulxyz.xyprt.model.DrawingElement
import io.github.soulxyz.xyprt.model.LabelTemplate
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawingTemplateJsonTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
        coerceInputValues = true
    }

    @Test fun drawingStaysVectorAcrossTemplateRoundTrip() {
        val codec = TemplateJson(json)
        val template = LabelTemplate(
            id = "draw-doc",
            name = "手写",
            elements = listOf(
                DrawingElement(
                    id = "ink",
                    widthPx = 120f,
                    heightPx = 60f,
                    strokes = listOf(
                        DrawStroke(
                            widthPx = 3f,
                            points = listOf(DrawPoint(1f, 2f), DrawPoint(20f, 18f), DrawPoint(60f, 8f)),
                        )
                    ),
                )
            ),
        )
        val decoded = codec.decode(codec.encode(template))
        assertTrue(decoded.elements.single() is DrawingElement)
        val drawing = decoded.elements.single() as DrawingElement
        assertEquals(1, drawing.strokes.size)
        assertEquals(3, drawing.strokes.single().points.size)
        assertEquals(3f, drawing.strokes.single().widthPx)
    }
}
