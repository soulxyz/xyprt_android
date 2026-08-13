package io.github.soulxyz.xyprt.ui.quickprint

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

private enum class CropHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE }

/** Lightweight, dependency-free crop surface for a freshly captured photo. */
@Composable
fun PhotoCropEditor(
    uri: Uri,
    crop: CropRect,
    onCropChange: (CropRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { QuickPrintRenderer.previewBitmap(context, uri, 1400) }.getOrNull()
        }
    }
    val density = LocalDensity.current
    val hitPx = with(density) { 34.dp.toPx() }
    val handleRadius = with(density) { 7.dp.toPx() }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var active by remember { mutableStateOf<CropHandle?>(null) }
    var dragStartRect by remember { mutableStateOf(crop) }
    var dragStartPos by remember { mutableStateOf(Offset.Zero) }
    val latestCrop by rememberUpdatedState(crop)
    val primary = MaterialTheme.colorScheme.primary
    val cropBackground = MaterialTheme.colorScheme.surfaceContainerHighest

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(360.dp)
            .background(cropBackground, RoundedCornerShape(20.dp))
            .onSizeChanged { boxSize = it },
    ) {
        val bmp = bitmap
        if (bmp != null && boxSize.width > 0 && boxSize.height > 0) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.Medium,
                modifier = Modifier.fillMaxSize(),
            )

            val scale = min(boxSize.width / bmp.width.toFloat(), boxSize.height / bmp.height.toFloat())
            val drawW = bmp.width * scale
            val drawH = bmp.height * scale
            val originX = (boxSize.width - drawW) / 2f
            val originY = (boxSize.height - drawH) / 2f

            fun point(nx: Float, ny: Float) = Offset(originX + nx * drawW, originY + ny * drawH)
            fun normalized(pos: Offset) = Offset(
                ((pos.x - originX) / drawW).coerceIn(0f, 1f),
                ((pos.y - originY) / drawH).coerceIn(0f, 1f),
            )
            fun corners(r: CropRect) = listOf(
                CropHandle.TOP_LEFT to point(r.left, r.top),
                CropHandle.TOP_RIGHT to point(r.right, r.top),
                CropHandle.BOTTOM_LEFT to point(r.left, r.bottom),
                CropHandle.BOTTOM_RIGHT to point(r.right, r.bottom),
            )
            fun chooseHandle(pos: Offset, rect: CropRect): CropHandle? {
                val nearest = corners(rect).minByOrNull { (_, p) -> (p - pos).getDistance() }
                if (nearest != null && (nearest.second - pos).getDistance() <= hitPx) return nearest.first
                val tl = point(rect.left, rect.top)
                val br = point(rect.right, rect.bottom)
                return if (pos.x in tl.x..br.x && pos.y in tl.y..br.y) CropHandle.MOVE else null
            }
            fun updateFor(handle: CropHandle, pos: Offset, start: CropRect, totalDrag: Offset): CropRect {
                val n = normalized(pos)
                val minSize = 0.06f
                return when (handle) {
                    CropHandle.TOP_LEFT -> start.copy(
                        left = n.x.coerceAtMost(start.right - minSize),
                        top = n.y.coerceAtMost(start.bottom - minSize),
                    )
                    CropHandle.TOP_RIGHT -> start.copy(
                        right = n.x.coerceAtLeast(start.left + minSize),
                        top = n.y.coerceAtMost(start.bottom - minSize),
                    )
                    CropHandle.BOTTOM_LEFT -> start.copy(
                        left = n.x.coerceAtMost(start.right - minSize),
                        bottom = n.y.coerceAtLeast(start.top + minSize),
                    )
                    CropHandle.BOTTOM_RIGHT -> start.copy(
                        right = n.x.coerceAtLeast(start.left + minSize),
                        bottom = n.y.coerceAtLeast(start.top + minSize),
                    )
                    CropHandle.MOVE -> {
                        val dx = totalDrag.x / drawW
                        val dy = totalDrag.y / drawH
                        val w = start.right - start.left
                        val h = start.bottom - start.top
                        val left = (start.left + dx).coerceIn(0f, 1f - w)
                        val top = (start.top + dy).coerceIn(0f, 1f - h)
                        CropRect(left, top, left + w, top + h)
                    }
                }.normalized(minSize)
            }

            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(uri, boxSize) {
                        detectDragGestures(
                            onDragStart = {
                                dragStartRect = latestCrop
                                dragStartPos = it
                                active = chooseHandle(it, latestCrop)
                            },
                            onDragEnd = { active = null },
                            onDragCancel = { active = null },
                            onDrag = { change, _ ->
                                val h = active ?: return@detectDragGestures
                                change.consume()
                                onCropChange(updateFor(h, change.position, dragStartRect, change.position - dragStartPos))
                            },
                        )
                    }
            ) {
                val tl = point(crop.left, crop.top)
                val br = point(crop.right, crop.bottom)
                val shade = Color.Black.copy(alpha = 0.48f)
                // Four simple rectangles avoid path/op complexity and keep this reliable on old devices.
                drawRect(shade, Offset(originX, originY), Size(drawW, (tl.y - originY).coerceAtLeast(0f)))
                drawRect(shade, Offset(originX, br.y), Size(drawW, (originY + drawH - br.y).coerceAtLeast(0f)))
                drawRect(shade, Offset(originX, tl.y), Size((tl.x - originX).coerceAtLeast(0f), (br.y - tl.y).coerceAtLeast(0f)))
                drawRect(shade, Offset(br.x, tl.y), Size((originX + drawW - br.x).coerceAtLeast(0f), (br.y - tl.y).coerceAtLeast(0f)))
                drawRect(
                    color = primary,
                    topLeft = tl,
                    size = Size((br.x - tl.x).coerceAtLeast(1f), (br.y - tl.y).coerceAtLeast(1f)),
                    style = Stroke(width = 2.dp.toPx()),
                )
                corners(crop).forEach { (_, p) ->
                    drawCircle(Color.White, radius = handleRadius + 2.dp.toPx(), center = p)
                    drawCircle(primary, radius = handleRadius, center = p)
                }
            }
        }
    }
}
