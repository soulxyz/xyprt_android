package io.github.soulxyz.xyprt.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.soulxyz.xyprt.model.DrawPoint
import io.github.soulxyz.xyprt.model.DrawStroke
import io.github.soulxyz.xyprt.model.DrawingElement
import java.util.UUID
import kotlin.math.max

private data class UiStroke(val points: List<Offset>, val width: Float)
private const val LOGICAL_WIDTH = 352f
private const val LOGICAL_HEIGHT = 220f

/** Freehand pad that keeps strokes as vectors until the final printer/export renderer. */
@Composable
fun SketchPadSheet(
    onDone: (DrawingElement) -> Unit,
    onCancel: () -> Unit,
) {
    var strokes by remember { mutableStateOf<List<UiStroke>>(emptyList()) }
    var active by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var penWidth by remember { mutableStateOf(4f) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    fun toLogical(p: Offset): Offset {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return Offset.Zero
        return Offset(
            p.x / canvasSize.width * LOGICAL_WIDTH,
            p.y / canvasSize.height * LOGICAL_HEIGHT,
        )
    }

    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("自由涂画", style = MaterialTheme.typography.headlineSmall)
        Text("笔迹会以矢量保存，放大、旋转或分享时不会先变成低清图片。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2f to "细", 4f to "中", 7f to "粗").forEach { (w, label) ->
                FilterChip(selected = penWidth == w, onClick = { penWidth = w }, label = { Text(label) })
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { if (strokes.isNotEmpty()) strokes = strokes.dropLast(1) }, enabled = strokes.isNotEmpty()) { Text("撤销") }
            OutlinedButton(onClick = { strokes = emptyList(); active = emptyList() }, enabled = strokes.isNotEmpty() || active.isNotEmpty()) { Text("清空") }
        }

        Canvas(
            Modifier.fillMaxWidth().height(260.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .onSizeChanged { canvasSize = it }
                .pointerInput(penWidth, canvasSize) {
                    detectDragGestures(
                        onDragStart = { p -> active = listOf(toLogical(p)) },
                        onDrag = { change, _ -> change.consume(); active = active + toLogical(change.position) },
                        onDragEnd = {
                            if (active.isNotEmpty()) strokes = strokes + UiStroke(active, penWidth)
                            active = emptyList()
                        },
                        onDragCancel = { active = emptyList() },
                    )
                },
        ) {
            val sx = size.width / LOGICAL_WIDTH
            val sy = size.height / LOGICAL_HEIGHT
            fun drawStroke(stroke: UiStroke) {
                val pts = stroke.points
                if (pts.size == 1) {
                    drawCircle(Color.Black, radius = stroke.width * (sx + sy) / 4f, center = Offset(pts[0].x * sx, pts[0].y * sy))
                } else if (pts.size > 1) {
                    val path = Path().apply {
                        moveTo(pts[0].x * sx, pts[0].y * sy)
                        for (i in 1 until pts.size) {
                            val a = pts[i - 1]
                            val b = pts[i]
                            quadraticBezierTo(a.x * sx, a.y * sy, (a.x + b.x) / 2f * sx, (a.y + b.y) / 2f * sy)
                        }
                        lineTo(pts.last().x * sx, pts.last().y * sy)
                    }
                    drawPath(path, Color.Black, style = Stroke(width = stroke.width * (sx + sy) / 2f, cap = StrokeCap.Round))
                }
            }
            strokes.forEach(::drawStroke)
            if (active.isNotEmpty()) drawStroke(UiStroke(active, penWidth))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(
                onClick = {
                    val vector = cropDrawing(
                        DrawingElement(
                            id = UUID.randomUUID().toString(),
                            strokes = strokes.map { stroke ->
                                DrawStroke(stroke.points.map { DrawPoint(it.x, it.y) }, stroke.width)
                            },
                        )
                    )
                    onDone(vector)
                },
                enabled = strokes.isNotEmpty(),
                modifier = Modifier.weight(2f),
            ) { Text("添加到纸上") }
        }
    }
}

private fun cropDrawing(element: DrawingElement): DrawingElement {
    val points = element.strokes.flatMap { it.points }
    if (points.isEmpty()) return element
    val maxStroke = element.strokes.maxOfOrNull { it.widthPx } ?: 3f
    val pad = max(5f, maxStroke * 2f)
    val minX = (points.minOf { it.x } - pad).coerceAtLeast(0f)
    val minY = (points.minOf { it.y } - pad).coerceAtLeast(0f)
    val maxX = (points.maxOf { it.x } + pad).coerceAtMost(LOGICAL_WIDTH)
    val maxY = (points.maxOf { it.y } + pad).coerceAtMost(LOGICAL_HEIGHT)
    return element.copy(
        widthPx = max(12f, maxX - minX),
        heightPx = max(12f, maxY - minY),
        strokes = element.strokes.map { stroke ->
            stroke.copy(points = stroke.points.map { it.copy(x = it.x - minX, y = it.y - minY) })
        },
    )
}
