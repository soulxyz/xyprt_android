package io.github.soulxyz.xyprt.scanner

import kotlin.math.abs
import kotlin.math.hypot

data class QuadPoint(val x: Float, val y: Float) {
    fun clamped() = QuadPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}

data class DocumentQuad(
    val topLeft: QuadPoint = QuadPoint(0.04f, 0.04f),
    val topRight: QuadPoint = QuadPoint(0.96f, 0.04f),
    val bottomRight: QuadPoint = QuadPoint(0.96f, 0.96f),
    val bottomLeft: QuadPoint = QuadPoint(0.04f, 0.96f),
) {
    fun points() = listOf(topLeft, topRight, bottomRight, bottomLeft)
    fun clamped() = copy(topLeft = topLeft.clamped(), topRight = topRight.clamped(), bottomRight = bottomRight.clamped(), bottomLeft = bottomLeft.clamped())
    val area: Float get() {
        val p=points(); var s=0f
        for(i in p.indices){ val a=p[i]; val b=p[(i+1)%p.size]; s += a.x*b.y-b.x*a.y }
        return abs(s)*0.5f
    }
    fun isReasonable(): Boolean {
        if(area < 0.03f) return false
        val p=points(); val cross=(0..3).map { i ->
            val a=p[i];val b=p[(i+1)%4];val c=p[(i+2)%4]
            (b.x-a.x)*(c.y-b.y)-(b.y-a.y)*(c.x-b.x)
        }
        val convex=cross.all{it>0f}||cross.all{it<0f}
        val edges=(0..3).map { i -> val a=p[i];val b=p[(i+1)%4];hypot((a.x-b.x).toDouble(),(a.y-b.y).toDouble()).toFloat() }
        return convex && edges.minOrNull()!! > 0.06f
    }
}

enum class ScanEngine { STANDARD, ENHANCED }

data class ScanDetection(val quad: DocumentQuad, val engine: ScanEngine, val confidence: Float)
