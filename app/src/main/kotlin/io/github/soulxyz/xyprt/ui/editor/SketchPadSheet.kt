package io.github.soulxyz.xyprt.ui.editor

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path as AndroidPath
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

private data class SketchStroke(val points: List<Offset>, val width: Float)

/** Simple freehand pad. The result becomes a normal ImageElement, so all existing image tools work. */
@Composable
fun SketchPadSheet(
    onDone: (ImageImport.Loaded) -> Unit,
    onCancel: () -> Unit,
) {
    var strokes by remember { mutableStateOf<List<SketchStroke>>(emptyList()) }
    var active by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var penWidth by remember { mutableStateOf(4f) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("自由涂画", style = MaterialTheme.typography.headlineSmall)
        Text("用手指写写画画，完成后还能继续缩放和旋转。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                .pointerInput(penWidth) {
                    detectDragGestures(
                        onDragStart = { p -> active = listOf(p) },
                        onDrag = { change, _ -> change.consume(); active = active + change.position },
                        onDragEnd = {
                            if (active.isNotEmpty()) strokes = strokes + SketchStroke(active, penWidth)
                            active = emptyList()
                        },
                        onDragCancel = { active = emptyList() },
                    )
                },
        ) {
            fun drawStroke(stroke: SketchStroke) {
                if (stroke.points.size == 1) {
                    drawCircle(ComposeColor.Black, radius = stroke.width / 2f, center = stroke.points.first())
                } else if (stroke.points.size > 1) {
                    val path = Path().apply {
                        moveTo(stroke.points.first().x, stroke.points.first().y)
                        stroke.points.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(path, ComposeColor.Black, style = Stroke(width = stroke.width, cap = StrokeCap.Round))
                }
            }
            strokes.forEach(::drawStroke)
            if (active.isNotEmpty()) drawStroke(SketchStroke(active, penWidth))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(
                onClick = {
                    if (canvasSize.width <= 0 || canvasSize.height <= 0) return@Button
                    val sourceW = canvasSize.width.toFloat()
                    val sourceH = canvasSize.height.toFloat()
                    val targetW = 384
                    val targetH = (targetW * sourceH / sourceW).toInt().coerceAtLeast(1)
                    val scale = targetW / sourceW
                    val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                    val c = AndroidCanvas(bitmap).apply { drawColor(Color.WHITE) }
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                    }
                    strokes.forEach { stroke ->
                        paint.strokeWidth = stroke.width * scale
                        if (stroke.points.size == 1) {
                            paint.style = Paint.Style.FILL
                            val p = stroke.points.first()
                            c.drawCircle(p.x * scale, p.y * scale, paint.strokeWidth / 2f, paint)
                            paint.style = Paint.Style.STROKE
                        } else if (stroke.points.size > 1) {
                            val path = AndroidPath().apply {
                                val first = stroke.points.first(); moveTo(first.x * scale, first.y * scale)
                                stroke.points.drop(1).forEach { lineTo(it.x * scale, it.y * scale) }
                            }
                            c.drawPath(path, paint)
                        }
                    }
                    onDone(ImageImport.fromBitmap(bitmap))
                    bitmap.recycle()
                },
                enabled = strokes.isNotEmpty(),
                modifier = Modifier.weight(2f),
            ) { Text("添加到纸上") }
        }
    }
}
