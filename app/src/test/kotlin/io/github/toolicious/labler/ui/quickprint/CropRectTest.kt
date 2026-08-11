package io.github.toolicious.labler.ui.quickprint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropRectTest {
    @Test fun normalizesReversedAndOutOfBoundsEdges() {
        val r = CropRect(1.2f, 0.9f, -0.2f, 0.1f).normalized()
        assertEquals(0f, r.left, 0.0001f)
        assertEquals(0.1f, r.top, 0.0001f)
        assertEquals(1f, r.right, 0.0001f)
        assertEquals(0.9f, r.bottom, 0.0001f)
    }

    @Test fun guaranteesMinimumCropSize() {
        val r = CropRect(0.5f, 0.5f, 0.51f, 0.51f).normalized(0.08f)
        assertTrue(r.right - r.left >= 0.079f)
        assertTrue(r.bottom - r.top >= 0.079f)
    }
}
