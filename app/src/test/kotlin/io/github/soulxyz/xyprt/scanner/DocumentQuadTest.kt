package io.github.soulxyz.xyprt.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentQuadTest {
    @Test fun rectangleAreaAndValidity() {
        val q = DocumentQuad(QuadPoint(.1f,.2f), QuadPoint(.9f,.2f), QuadPoint(.9f,.8f), QuadPoint(.1f,.8f))
        assertEquals(.48f, q.area, .0001f)
        assertTrue(q.isReasonable())
    }

    @Test fun selfCrossingQuadIsRejected() {
        val q = DocumentQuad(QuadPoint(.1f,.1f), QuadPoint(.9f,.9f), QuadPoint(.9f,.1f), QuadPoint(.1f,.9f))
        assertFalse(q.isReasonable())
    }

    @Test fun tinyQuadIsRejected() {
        val q = DocumentQuad(QuadPoint(.49f,.49f), QuadPoint(.51f,.49f), QuadPoint(.51f,.51f), QuadPoint(.49f,.51f))
        assertFalse(q.isReasonable())
    }

    @Test fun clampedNeverLeavesNormalizedSpace() {
        val q = DocumentQuad(QuadPoint(-1f,-.2f), QuadPoint(2f,-1f), QuadPoint(3f,2f), QuadPoint(-.4f,1.4f)).clamped()
        assertTrue(q.points().all { it.x in 0f..1f && it.y in 0f..1f })
    }
}
