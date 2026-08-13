package io.github.soulxyz.xyprt.ui.quickprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.github.soulxyz.xyprt.printer.MonoImage
import io.github.soulxyz.xyprt.printer.Protocol
import io.github.soulxyz.xyprt.printer.dither.Canny
import io.github.soulxyz.xyprt.printer.dither.Contrast
import io.github.soulxyz.xyprt.printer.dither.DitherMode
import io.github.soulxyz.xyprt.printer.dither.Ditherer
import io.github.soulxyz.xyprt.printer.dither.Outline
import io.github.soulxyz.xyprt.printer.dither.InkRemoval
import io.github.soulxyz.xyprt.printer.dither.OutlineMethod
import kotlin.math.roundToInt

/** Processing options shared by quick-image and PDF printing. */
data class QuickImageAdjustments(
    val mode: DitherMode = DitherMode.FLOYD_STEINBERG,
    val threshold: Int = 155,
    val contrast: Int = 0,
    val invert: Boolean = false,
    val outlineSensitivity: Int = 88,
    val outlineThickness: Int = 1,
    val outlineMethod: OutlineMethod = OutlineMethod.CANNY,
    val outlineSmooth: Boolean = false,
    val rotationDegrees: Int = 0,
    val scalePercent: Int = 100,
    val removeRedInk: Boolean = false,
    val removeBlueInk: Boolean = false,
)

enum class QuickTextFont { SANS, SERIF, MONO }
enum class QuickTextAlign { LEFT, CENTER, RIGHT }

data class QuickTextStyle(
    val fontSizePx: Int = 30,
    val lineSpacingPercent: Int = 115,
    val font: QuickTextFont = QuickTextFont.SANS,
    val align: QuickTextAlign = QuickTextAlign.LEFT,
)

/** Render arbitrary shared content into the BY-288's portrait paper coordinate system. */
object QuickPrintRenderer {
    // Keep only a very small safety edge. 16 px/side made documents needlessly tiny.
    private const val EDGE_MARGIN = 4
    private const val CONTENT_WIDTH = Protocol.HEAD_DOTS - EDGE_MARGIN * 2
    private const val MAX_HEIGHT = 60_000
    private const val PDF_RENDER_WIDTH = Protocol.HEAD_DOTS * 2
    private const val PDF_PAGE_GAP = 16

    fun text(text: String, style: QuickTextStyle = QuickTextStyle()): Bitmap {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = style.fontSizePx.coerceIn(16, 72).toFloat()
            typeface = when (style.font) {
                QuickTextFont.SANS -> Typeface.SANS_SERIF
                QuickTextFont.SERIF -> Typeface.SERIF
                QuickTextFont.MONO -> Typeface.MONOSPACE
            }
        }
        val body = text.ifBlank { "请输入要打印的文字" }
        val alignment = when (style.align) {
            QuickTextAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
            QuickTextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            QuickTextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }
        val layout = StaticLayout.Builder.obtain(body, 0, body.length, paint, CONTENT_WIDTH)
            .setAlignment(alignment)
            .setIncludePad(true)
            .setLineSpacing(0f, style.lineSpacingPercent.coerceIn(80, 200) / 100f)
            .build()
        val h = (layout.height + EDGE_MARGIN * 2).coerceIn(64, MAX_HEIGHT)
        return Bitmap.createBitmap(Protocol.HEAD_DOTS, h, Bitmap.Config.ARGB_8888).also { out ->
            Canvas(out).apply {
                drawColor(Color.WHITE)
                save()
                translate(EDGE_MARGIN.toFloat(), EDGE_MARGIN.toFloat())
                layout.draw(this)
                restore()
            }
        }
    }

    fun todo(title: String, itemsText: String): Bitmap {
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
        }
        val itemPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 29f
            typeface = Typeface.SANS_SERIF
        }
        val items = itemsText.lineSequence().map { it.trim().removePrefix("- ").removePrefix("• ") }
            .filter { it.isNotBlank() }.take(40).toList()
        val safeTitle = title.trim().ifBlank { "今日待办" }
        val contentW = CONTENT_WIDTH
        val itemTextW = (contentW - 38).coerceAtLeast(80)
        val titleLayout = StaticLayout.Builder.obtain(safeTitle, 0, safeTitle.length, titlePaint, contentW)
            .setIncludePad(true).build()
        val itemLayouts = items.map { item ->
            StaticLayout.Builder.obtain(item, 0, item.length, itemPaint, itemTextW)
                .setIncludePad(true).setLineSpacing(1f, 1.08f).build()
        }
        val gap = 12
        val emptyHint = if (items.isEmpty()) 54 else 0
        val h = (EDGE_MARGIN * 2 + titleLayout.height + 18 + itemLayouts.sumOf { maxOf(it.height, 34) + gap } + emptyHint)
            .coerceIn(80, MAX_HEIGHT)
        return Bitmap.createBitmap(Protocol.HEAD_DOTS, h, Bitmap.Config.ARGB_8888).also { out ->
            val c = Canvas(out)
            c.drawColor(Color.WHITE)
            c.save()
            c.translate(EDGE_MARGIN.toFloat(), EDGE_MARGIN.toFloat())
            titleLayout.draw(c)
            var y = titleLayout.height + 18f
            if (items.isEmpty()) {
                itemPaint.color = Color.GRAY
                c.drawText("添加待办事项后即可打印", 0f, y + 32f, itemPaint)
            } else {
                itemLayouts.forEach { layout ->
                    val boxTop = y + 4f
                    val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        style = Paint.Style.STROKE
                        strokeWidth = 2.2f
                    }
                    c.drawRect(2f, boxTop, 25f, boxTop + 23f, boxPaint)
                    c.save()
                    c.translate(38f, y)
                    layout.draw(c)
                    c.restore()
                    y += maxOf(layout.height, 34) + gap
                }
            }
            c.restore()
        }
    }

    /** Raw color crop used when a camera draft is saved into the free-layout editor. */
    fun editorImage(context: Context, uri: Uri, crop: CropRect, maxSide: Int = 1200): Bitmap {
        val src = decodeSampled(context, uri, 3200) ?: error("无法读取图片")
        val cropped = cropBitmap(src, crop.normalized())
        if (cropped !== src) src.recycle()
        val scale = minOf(1f, maxSide.toFloat() / maxOf(cropped.width, cropped.height).coerceAtLeast(1))
        if (scale >= 0.999f) return cropped
        val w = (cropped.width * scale).roundToInt().coerceAtLeast(1)
        val h = (cropped.height * scale).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createScaledBitmap(cropped, w, h, true)
        cropped.recycle()
        return out
    }

    fun previewBitmap(context: Context, uri: Uri, maxDimension: Int = 1600): Bitmap =
        decodeSampled(context, uri, maxDimension, Bitmap.Config.RGB_565) ?: error("无法读取图片")

    fun image(
        context: Context,
        uri: Uri,
        rotationDegrees: Int = 0,
        scalePercent: Int = 100,
        crop: CropRect = CropRect(),
    ): Bitmap {
        val src = decodeSampled(context, uri, 3200) ?: error("无法读取图片")
        val cropped = cropBitmap(src, crop.normalized())
        if (cropped !== src) src.recycle()
        val rotated = rotate(cropped, rotationDegrees)
        if (rotated !== cropped) cropped.recycle()
        val out = fitBitmapNoRecycle(rotated, scalePercent)
        rotated.recycle()
        return out
    }

    fun images(context: Context, uris: List<Uri>, rotationDegrees: Int = 0, scalePercent: Int = 100): Bitmap {
        val pages = uris.take(20).map { image(context, it, rotationDegrees, scalePercent) }
        return stack(pages)
    }

    /**
     * PDF is rendered above printer resolution first, optionally cropped to the real page content,
     * then downsampled once. This is substantially sharper than rendering the full A4 page directly
     * into ~350 pixels, especially for small text.
     */
    fun pdf(context: Context, uri: Uri, autoCropWhiteMargins: Boolean = true, rotationDegrees: Int = 0, scalePercent: Int = 100): Bitmap {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("无法打开 PDF")
        val rendered = mutableListOf<Bitmap>()
        PdfRenderer(pfd).use { pdf ->
            var used = 0
            for (i in 0 until minOf(pdf.pageCount, 20)) {
                pdf.openPage(i).use { page ->
                    val renderW = PDF_RENDER_WIDTH
                    val renderH = (page.height * (renderW / page.width.toFloat())).roundToInt().coerceAtLeast(1)
                    val hi = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
                    Canvas(hi).drawColor(Color.WHITE)
                    page.render(hi, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                    val content = if (autoCropWhiteMargins) cropWhiteMargins(hi) else hi
                    val rotated = rotate(content, rotationDegrees)
                    val fitted = fitBitmapNoRecycle(rotated, scalePercent)
                    if (rotated !== content) rotated.recycle()
                    if (content !== hi) content.recycle()
                    hi.recycle()

                    if (used + fitted.height > MAX_HEIGHT) {
                        fitted.recycle()
                        return@use
                    }
                    rendered += fitted
                    used += fitted.height + PDF_PAGE_GAP
                }
                if (used >= MAX_HEIGHT - 1024) break
            }
        }
        pfd.close()
        if (rendered.isEmpty()) error("PDF 没有可打印页面")
        return stack(rendered, PDF_PAGE_GAP)
    }

    /** Fit content to the paper; values above 100% zoom and crop around the center. */
    private fun fitBitmapNoRecycle(src: Bitmap, scalePercent: Int = 100): Bitmap {
        val pct = scalePercent.coerceIn(40, 200) / 100f
        val targetW = (CONTENT_WIDTH * pct).roundToInt().coerceAtLeast(1)
        val targetH = (src.height * (targetW / src.width.toFloat().coerceAtLeast(1f))).roundToInt()
            .coerceIn(1, MAX_HEIGHT - EDGE_MARGIN * 2)
        val scaled = Bitmap.createScaledBitmap(src, targetW, targetH, true)
        val visibleW = minOf(targetW, Protocol.HEAD_DOTS)
        val leftInScaled = ((targetW - visibleW) / 2).coerceAtLeast(0)
        val xOnPaper = ((Protocol.HEAD_DOTS - visibleW) / 2).coerceAtLeast(0)
        val out = Bitmap.createBitmap(Protocol.HEAD_DOTS, targetH + EDGE_MARGIN * 2, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(
                scaled,
                Rect(leftInScaled, 0, leftInScaled + visibleW, targetH),
                Rect(xOnPaper, EDGE_MARGIN, xOnPaper + visibleW, EDGE_MARGIN + targetH),
                null
            )
        }
        if (scaled !== src) scaled.recycle()
        return out
    }

    private fun decodeSampled(
        context: Context,
        uri: Uri,
        maxDimension: Int,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension * 2) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = config
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun cropBitmap(src: Bitmap, rect: CropRect): Bitmap {
        if (rect.isFull) return src
        val l = (rect.left * src.width).roundToInt().coerceIn(0, src.width - 1)
        val t = (rect.top * src.height).roundToInt().coerceIn(0, src.height - 1)
        val r = (rect.right * src.width).roundToInt().coerceIn(l + 1, src.width)
        val b = (rect.bottom * src.height).roundToInt().coerceIn(t + 1, src.height)
        return Bitmap.createBitmap(src, l, t, r - l, b - t)
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return src
        val m = Matrix().apply { postRotate(d.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Crop only obvious near-white outer margins; keep a small breathing margin around content. */
    private fun cropWhiteMargins(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val row = IntArray(w)
        var left = w
        var right = -1
        var top = h
        var bottom = -1
        // Sampling every 2 pixels makes large multi-page PDFs much cheaper while still finding text.
        var y = 0
        while (y < h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val p = row[x]
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                if (r < 246 || g < 246 || b < 246) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
                x += 2
            }
            y += 2
        }
        if (right < left || bottom < top) return src
        val padX = (w * 0.018f).roundToInt().coerceAtLeast(8)
        val padY = (h * 0.012f).roundToInt().coerceAtLeast(8)
        left = (left - padX).coerceAtLeast(0)
        right = (right + padX).coerceAtMost(w - 1)
        top = (top - padY).coerceAtLeast(0)
        bottom = (bottom + padY).coerceAtMost(h - 1)
        // If the detected crop would barely change anything, preserve the original page.
        if ((right - left + 1) > w * 0.94 && (bottom - top + 1) > h * 0.94) return src
        return Bitmap.createBitmap(src, left, top, right - left + 1, bottom - top + 1)
    }

    private fun stack(parts: List<Bitmap>, gap: Int = EDGE_MARGIN * 2): Bitmap {
        if (parts.size == 1 && parts[0].width == Protocol.HEAD_DOTS) return parts[0]
        val total = (parts.sumOf { it.height } + gap * (parts.size - 1)).coerceAtMost(MAX_HEIGHT)
        val out = Bitmap.createBitmap(Protocol.HEAD_DOTS, total.coerceAtLeast(64), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        var y = 0
        parts.forEachIndexed { index, p ->
            if (y >= total) return@forEachIndexed
            val h = minOf(p.height, total - y)
            c.drawBitmap(p, null, Rect(0, y, Protocol.HEAD_DOTS, y + h), null)
            y += h
            if (index != parts.lastIndex) y += gap
            p.recycle()
        }
        return out
    }

    /** Same image-processing family used by the normal editor: outline, threshold, FS and Atkinson. */
    fun toMono(bitmap: Bitmap, a: QuickImageAdjustments): MonoImage {
        require(bitmap.width == Protocol.HEAD_DOTS)
        val w = bitmap.width
        val h = bitmap.height
        val rawPx = IntArray(w * h)
        bitmap.getPixels(rawPx, 0, w, 0, 0, w, h)
        val px = InkRemoval.apply(rawPx, a.removeRedInk, a.removeBlueInk)
        val opaque = BooleanArray(px.size) { (px[it] ushr 24) >= 128 }
        val black = if (a.mode == DitherMode.OUTLINE) {
            val edge = when (a.outlineMethod) {
                OutlineMethod.CANNY -> Canny.detect(px, opaque, w, h, a.outlineSensitivity, a.outlineThickness, smooth = a.outlineSmooth)
                OutlineMethod.LINES -> Outline.trace(px, opaque, w, h, a.outlineSensitivity, a.outlineThickness, borderIsBackground = false, smooth = a.outlineSmooth)
            }
            if (a.invert) BooleanArray(edge.size) { !edge[it] } else edge
        } else {
            val gray = FloatArray(px.size) { i ->
                val p = px[i]
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                val lum = r * 0.299f + g * 0.587f + b * 0.114f
                if (a.invert) 255f - lum else lum
            }
            when (a.mode) {
                DitherMode.THRESHOLD -> BooleanArray(gray.size) { gray[it] < a.threshold }
                else -> Ditherer.of(a.mode).dither(Contrast.adjust(gray, a.contrast), w, h)
            }
        }
        return MonoImage(h, black).trimTrailingWhite()
    }
}
