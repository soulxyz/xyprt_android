package io.github.toolicious.labler.data

import io.github.toolicious.labler.model.LabelSpec
import io.github.toolicious.labler.model.LabelTemplate
import io.github.toolicious.labler.model.TableElement
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TableTemplateJsonTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
        coerceInputValues = true
    }

    @Test
    fun tableElementSurvivesTemplateExportRoundTrip() {
        val codec = TemplateJson(json)
        val template = LabelTemplate(
            id = "t1",
            name = "课程表",
            spec = LabelSpec(),
            elements = listOf(
                TableElement(
                    id = "grid",
                    x = 12f,
                    y = 24f,
                    rows = 5,
                    columns = 4,
                    widthPx = 320f,
                    heightPx = 180f,
                    strokePx = 3f,
                )
            ),
        )

        val decoded = codec.decode(codec.encode(template))
        assertEquals(1, decoded.elements.size)
        assertTrue(decoded.elements.first() is TableElement)
        val grid = decoded.elements.first() as TableElement
        assertEquals(5, grid.rows)
        assertEquals(4, grid.columns)
        assertEquals(320f, grid.widthPx)
        assertEquals(180f, grid.heightPx)
        assertEquals(3f, grid.strokePx)
    }
}
