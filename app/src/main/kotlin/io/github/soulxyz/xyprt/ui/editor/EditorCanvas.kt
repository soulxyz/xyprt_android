package io.github.soulxyz.xyprt.ui.editor

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.material3.MaterialTheme
import io.github.soulxyz.xyprt.model.FrameElement
import io.github.soulxyz.xyprt.model.FrameStyle
import io.github.soulxyz.xyprt.model.LabelElement
import io.github.soulxyz.xyprt.model.LabelSpec
import io.github.soulxyz.xyprt.model.LabelTextAlign
import io.github.soulxyz.xyprt.model.TextElement
import io.github.soulxyz.xyprt.printer.MediaType
import io.github.soulxyz.xyprt.render.LabelRenderer
import io.github.soulxyz.xyprt.render.MonoConverter
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Axis-aligned bounding box of an element rotated by an arbitrary angle. */
private fun elementBounds(el: LabelElement): Rect {
    val s = LabelRenderer.measure(el)
    val cx = el.x + s.width / 2f
    val cy = el.y + s.height / 2f
    val rad = Math.toRadians(el.rotation.toDouble())
    val c = abs(cos(rad)).toFloat()
    val sn = abs(sin(rad)).toFloat()
    val hw = s.width / 2f * c + s.height / 2f * sn
    val hh = s.width / 2f * sn + s.height / 2f * c
    return Rect(cx - hw, cy - hh, cx + hw, cy + hh)
}

/**
 * Canvas-first editor surface. The paper is rendered at print proportions and every object can be
 * selected and manipulated directly: drag to move, top-right to rotate and bottom-right to resize.
 * The top-left handle deletes the selected object. All coordinates are printer dots.
 */
@Composable
fun EditorCanvas(
    spec: LabelSpec,
    elements: List<LabelElement>,
    selectedId: String?,
    guides: SnapGuides,
    onSelect: (String?) -> Unit,
    onDoubleTap: (String) -> Unit,
    onDeleteSelected: () -> Unit,
    onDragStart: (String, Boolean) -> Unit,
    onDragBy: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onResizeStart: (String) -> Unit,
    onResizeBy: (Offset) -> Unit,
    onResizeEnd: () -> Unit,
    onRotateStart: (String) -> Unit,
    onRotateTo: (Int) -> Unit,
    onRotateEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var snapFreeDrag by remember { mutableStateOf(false) }

    val labelW = LabelSpec.PRINT_WIDTH_PX.toFloat()
    val labelH = spec.lengthPx.toFloat()
    val isDieCut = spec.media == MediaType.DIE_CUT
    val cornerR = 12f
    val total = if (boxSize.width > 0 && boxSize.height > 0) {
        min(boxSize.width / labelW, boxSize.height / labelH) * 0.94f
    } else 1f
    val contentTL = Offset(
        (boxSize.width - labelW * total) / 2f,
        (boxSize.height - labelH * total) / 2f,
    )

    // Show the unselected content through the exact 384-dot / 1-bit print pipeline.
    // The selected element stays vector while editing, so it remains clear when moving or resizing.
    val others = elements.filter { it.id != selectedId }
    val base = remember(spec, others) { MonoConverter.toBitmap(LabelRenderer.renderMono(spec, others)) }
    val basePaint = remember(total < 1f) {
        Paint().apply { isAntiAlias = false; isFilterBitmap = total < 1f }
    }

    val elementsState = rememberUpdatedState(elements)
    val selectedIdState = rememberUpdatedState(selectedId)
    val totalState = rememberUpdatedState(total)
    val tlState = rememberUpdatedState(contentTL)

    val background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    val selectionColor = MaterialTheme.colorScheme.primary
    val deleteColor = MaterialTheme.colorScheme.error
    val guideColor = MaterialTheme.colorScheme.tertiary
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    val handleRadiusLabel = 20f

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                var mode = 0 // 0 nothing, 1 move, 2 resize, 3 rotate
                var rotateCenter = Offset.Zero
                var rotateStartTouch = 0f
                var rotateStartDegrees = 0

                detectDragGesturesWithDoubleTap(
                    onStart = { pos, snapFree ->
                        val sc = totalState.value.coerceAtLeast(0.001f)
                        val lp = (pos - tlState.value) / sc
                        val sel = elementsState.value.find { it.id == selectedIdState.value }
                        val b = sel?.let(::elementBounds)
                        val resizeHit = b != null && (lp - Offset(b.right, b.bottom)).getDistance() < handleRadiusLabel
                        val rotateHit = b != null && (lp - Offset(b.right, b.top)).getDistance() < handleRadiusLabel

                        when {
                            sel != null && rotateHit -> {
                                mode = 3
                                rotateCenter = b!!.center
                                rotateStartTouch = angleDegrees(lp, rotateCenter)
                                rotateStartDegrees = sel.rotation
                                onRotateStart(sel.id)
                            }
                            sel != null && resizeHit -> {
                                mode = 2
                                onResizeStart(sel.id)
                            }
                            sel != null && hitTest(lp, sel) -> {
                                mode = 1
                                snapFreeDrag = snapFree
                                onDragStart(sel.id, snapFree)
                            }
                            else -> {
                                val hit = elementsState.value.lastOrNull { hitTest(lp, it) }
                                if (hit != null) {
                                    mode = 1
                                    snapFreeDrag = snapFree
                                    onDragStart(hit.id, snapFree)
                                } else {
                                    mode = 0
                                    onSelect(null)
                                }
                            }
                        }
                    },
                    onDrag = { change, amount ->
                        val sc = totalState.value.coerceAtLeast(0.001f)
                        when (mode) {
                            1 -> onDragBy(amount / sc)
                            2 -> onResizeBy(amount / sc)
                            3 -> {
                                val lp = (change.position - tlState.value) / sc
                                val delta = normalizeDelta(angleDegrees(lp, rotateCenter) - rotateStartTouch)
                                val raw = normalizeDegrees(rotateStartDegrees + delta.roundToInt())
                                val nearest = ((raw + 7) / 15) * 15 % 360
                                val snapped = if (angularDistance(raw, nearest) <= 3) nearest else raw
                                onRotateTo(snapped)
                            }
                        }
                    },
                    onEnd = {
                        when (mode) {
                            1 -> onDragEnd()
                            2 -> onResizeEnd()
                            3 -> onRotateEnd()
                        }
                        mode = 0
                        snapFreeDrag = false
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { pos ->
                        val sc = totalState.value.coerceAtLeast(0.001f)
                        val lp = (pos - tlState.value) / sc
                        val hit = elementsState.value.lastOrNull { hitTest(lp, it) }
                        if (hit != null) {
                            onSelect(hit.id)
                            onDoubleTap(hit.id)
                        }
                    },
                    onTap = { pos ->
                        val sc = totalState.value.coerceAtLeast(0.001f)
                        val lp = (pos - tlState.value) / sc
                        val sel = elementsState.value.find { it.id == selectedIdState.value }
                        val deleteHit = sel?.let {
                            val b = elementBounds(it)
                            (lp - Offset(b.left, b.top)).getDistance() < handleRadiusLabel
                        } == true
                        if (deleteHit) {
                            onDeleteSelected()
                        } else {
                            onSelect(elementsState.value.lastOrNull { hitTest(lp, it) }?.id)
                        }
                    }
                )
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(background)

            // A restrained paper shadow makes the printable area read as a real sheet without
            // turning the editor into a decorative card UI.
            val paperTopLeft = contentTL
            val paperSize = Size(labelW * total, labelH * total)
            if (isDieCut) {
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.10f),
                    topLeft = paperTopLeft + Offset(0f, 4f),
                    size = paperSize,
                    cornerRadius = CornerRadius(cornerR * total, cornerR * total),
                )
            } else {
                drawRect(
                    color = Color.Black.copy(alpha = 0.10f),
                    topLeft = paperTopLeft + Offset(0f, 4f),
                    size = paperSize,
                )
            }

            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                val save = nc.save()
                nc.translate(contentTL.x, contentTL.y)
                nc.scale(total, total)
                if (isDieCut) {
                    val path = android.graphics.Path().apply {
                        addRoundRect(0f, 0f, labelW, labelH, cornerR, cornerR, android.graphics.Path.Direction.CW)
                    }
                    nc.clipPath(path)
                } else {
                    nc.clipRect(0f, 0f, labelW, labelH)
                }
                nc.drawBitmap(base, 0f, 0f, basePaint)
                elements.find { it.id == selectedId }?.let { LabelRenderer.drawElementInto(nc, it) }
                nc.restoreToCount(save)
            }

            if (isDieCut) {
                drawRoundRect(
                    color = outlineColor,
                    topLeft = contentTL,
                    size = paperSize,
                    cornerRadius = CornerRadius(cornerR * total, cornerR * total),
                    style = Stroke(width = 1f),
                )
            } else {
                drawRect(
                    color = outlineColor,
                    topLeft = contentTL,
                    size = paperSize,
                    style = Stroke(width = 1f),
                )
            }

            fun toScreen(lx: Float, ly: Float) = contentTL + Offset(lx * total, ly * total)

            val dash = PathEffect.dashPathEffect(floatArrayOf(9f, 6f))
            guides.xLine?.let { gx ->
                drawLine(guideColor, toScreen(gx, 0f), toScreen(gx, labelH), strokeWidth = 2f, pathEffect = dash)
            }
            guides.yLine?.let { gy ->
                drawLine(guideColor, toScreen(0f, gy), toScreen(labelW, gy), strokeWidth = 2f, pathEffect = dash)
            }

            val sel = elements.find { it.id == selectedId }
            if (sel != null) {
                val frameColor = if (snapFreeDrag) guideColor else selectionColor
                val b = elementBounds(sel)
                val topLeft = toScreen(b.left, b.top)
                val topRight = toScreen(b.right, b.top)
                val bottomRight = toScreen(b.right, b.bottom)
                drawRect(
                    color = frameColor,
                    topLeft = topLeft,
                    size = Size(b.width * total, b.height * total),
                    style = Stroke(width = 2.5f),
                )

                // Alignment anchor for text remains visible, but subtle.
                if (sel is TextElement) {
                    val ax = when (sel.align) {
                        LabelTextAlign.LEFT -> b.left
                        LabelTextAlign.CENTER -> (b.left + b.right) / 2f
                        LabelTextAlign.RIGHT -> b.right
                    }
                    drawLine(
                        color = selectionColor.copy(alpha = 0.45f),
                        start = toScreen(ax, b.top),
                        end = toScreen(ax, b.bottom),
                        strokeWidth = 2f,
                    )
                }

                // Delete handle.
                drawCircle(deleteColor, radius = 13f, center = topLeft)
                drawLine(Color.White, topLeft + Offset(-4.5f, -4.5f), topLeft + Offset(4.5f, 4.5f), 2.2f)
                drawLine(Color.White, topLeft + Offset(4.5f, -4.5f), topLeft + Offset(-4.5f, 4.5f), 2.2f)

                // Rotate handle.
                drawCircle(surfaceColor, radius = 13f, center = topRight)
                drawCircle(frameColor, radius = 13f, center = topRight, style = Stroke(width = 2.5f))
                drawCircle(frameColor, radius = 5f, center = topRight, style = Stroke(width = 2f))
                drawLine(frameColor, topRight + Offset(3f, -5f), topRight + Offset(7f, -3f), 2f)

                // Resize handle.
                drawCircle(surfaceColor, radius = 13f, center = bottomRight)
                drawCircle(frameColor, radius = 13f, center = bottomRight, style = Stroke(width = 2.5f))
                drawLine(frameColor, bottomRight + Offset(-5f, 5f), bottomRight + Offset(5f, -5f), 2f)
                drawLine(frameColor, bottomRight + Offset(0f, 5f), bottomRight + Offset(5f, 0f), 2f)
            }
        }
    }
}


/**
 * Recognises both a normal drag and "double tap, keep holding, then drag". The latter is used
 * for pixel-level placement and reports snapFree=true. Plain double taps are still handled by
 * the separate tap detector, so text/property editing keeps working.
 */
private suspend fun PointerInputScope.detectDragGesturesWithDoubleTap(
    onStart: (Offset, Boolean) -> Unit,
    onDrag: (PointerInputChange, Offset) -> Unit,
    onEnd: () -> Unit,
) = awaitEachGesture {
    val first = awaitFirstDown(requireUnconsumed = false)
    var start = first
    var snapFree = false
    var overSlop = Offset.Zero
    var slop = awaitTouchSlopOrCancellation(first.id) { change, over ->
        change.consume()
        overSlop = over
    }
    if (slop == null) {
        val second = awaitSecondDown(first) ?: return@awaitEachGesture
        snapFree = true
        start = second
        slop = awaitTouchSlopOrCancellation(second.id) { change, over ->
            change.consume()
            overSlop = over
        }
    }
    val dragStart = slop ?: return@awaitEachGesture

    onStart(start.position, snapFree)
    if (overSlop != Offset.Zero) onDrag(dragStart, overSlop)
    drag(dragStart.id) { change ->
        val delta = change.positionChange()
        if (delta != Offset.Zero) onDrag(change, delta)
        change.consume()
    }
    onEnd()
}

private suspend fun AwaitPointerEventScope.awaitSecondDown(
    first: PointerInputChange,
): PointerInputChange? = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
    val minUptime = first.uptimeMillis + viewConfiguration.doubleTapMinTimeMillis
    var change: PointerInputChange
    do {
        change = awaitFirstDown(requireUnconsumed = false)
    } while (change.uptimeMillis < minUptime)
    change
}

private fun angleDegrees(point: Offset, center: Offset): Float =
    Math.toDegrees(atan2((point.y - center.y).toDouble(), (point.x - center.x).toDouble())).toFloat()

private fun normalizeDegrees(value: Int): Int = ((value % 360) + 360) % 360

private fun normalizeDelta(value: Float): Float {
    var result = value
    while (result > 180f) result -= 360f
    while (result < -180f) result += 360f
    return result
}

private fun angularDistance(a: Int, b: Int): Int {
    val d = abs(a - b) % 360
    return minOf(d, 360 - d)
}

private fun hitTest(lp: Offset, el: LabelElement): Boolean {
    val s = LabelRenderer.measure(el)
    val cx = el.x + s.width / 2f
    val cy = el.y + s.height / 2f
    val rad = Math.toRadians(-el.rotation.toDouble())
    val cs = cos(rad).toFloat()
    val sn = sin(rad).toFloat()
    val dx = lp.x - cx
    val dy = lp.y - cy
    val local = Offset(cx + dx * cs - dy * sn, cy + dx * sn + dy * cs)
    val b = Rect(el.x, el.y, el.x + s.width, el.y + s.height)
    val pad = 6f
    if (el is FrameElement && (el.style == FrameStyle.RECT || el.style == FrameStyle.ROUND_RECT)) {
        val outer = b.inflate(pad)
        val inner = b.deflate(el.strokePx + pad)
        val insideInner = inner.width > 0f && inner.height > 0f && inner.contains(local)
        return outer.contains(local) && !insideInner
    }
    return b.inflate(pad).contains(local)
}
