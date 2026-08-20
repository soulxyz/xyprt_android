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
import android.media.ExifInterface
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
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Processing options shared by quick-image and PDF printing. */
enum class PaperPreset { ORIGINAL, BRIGHTEN, SHARPEN, DOCUMENT, GRAYSCALE }

data class QuickImageAdjustments(
    val mode: DitherMode = DitherMode.FLOYD_STEINBERG,
    val paperPreset: PaperPreset = PaperPreset.ORIGINAL,
    val threshold: Int = 155,
    val contrast: Int = 0,
    val invert: Boolean = false,
    val outlineSensitivity: Int = 88,
    val outlineThickness: Int = 1,
    val outlineMethod: OutlineMethod = OutlineMethod.CANNY,
    val outlineSmooth: Boolean = false,
    /** Rotation used to correct the source image itself. */
    val rotationDegrees: Int = 0,
    /** Rotate the finished content sideways for physical horizontal printing. */
    val landscapePrint: Boolean = false,
    val scalePercent: Int = 100,
    val removeRedInk: Boolean = false,
    val removeBlueInk: Boolean = false,
    /** Run the AI scan-normalization (enhance field + optional blackpoint) before printing. */
    val enhance: Boolean = false,
)

internal fun QuickImageAdjustments.outputRotationDegrees(): Int =
    ((rotationDegrees + if (landscapePrint) 90 else 0) % 360 + 360) % 360

enum class QuickTextFont { SANS, SERIF, MONO }
enum class QuickTextAlign { LEFT, CENTER, RIGHT }

data class QuickTextStyle(
    val fontSizePx: Int = 30,
    val lineSpacingPercent: Int = 115,
    val font: QuickTextFont = QuickTextFont.SANS,
    val align: QuickTextAlign = QuickTextAlign.LEFT,
)

/** Render arbitrary shared content into the BY-288's portrait paper coordinate system. */
enum class TodoPreset { CLEAN, COMPACT, FOCUS }

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

    fun todo(
        title: String,
        itemsText: String,
        style: QuickTextStyle = QuickTextStyle(fontSizePx = 29),
        preset: TodoPreset = TodoPreset.CLEAN,
        showDate: Boolean = true,
        dateText: String = "",
        centerTitle: Boolean = true,
    ): Bitmap {
        val titleSize = when (preset) {
            TodoPreset.CLEAN -> 34f
            TodoPreset.COMPACT -> 30f
            TodoPreset.FOCUS -> 38f
        }
        val itemSize = when (preset) {
            TodoPreset.CLEAN -> 29f
            TodoPreset.COMPACT -> 27f
            TodoPreset.FOCUS -> 31f
        }
        val gap = when (preset) {
            TodoPreset.CLEAN -> 10
            TodoPreset.COMPACT -> 6
            TodoPreset.FOCUS -> 14
        }
        val checkboxSize = when (preset) {
            TodoPreset.CLEAN -> 22f
            TodoPreset.COMPACT -> 20f
            TodoPreset.FOCUS -> 24f
        }
        val titleAlign = if (centerTitle) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = titleSize
            typeface = Typeface.DEFAULT_BOLD
        }
        val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 18f
            typeface = Typeface.SANS_SERIF
        }
        val itemPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = itemSize
            typeface = when (style.font) {
                QuickTextFont.SANS -> Typeface.SANS_SERIF
                QuickTextFont.SERIF -> Typeface.SERIF
                QuickTextFont.MONO -> Typeface.MONOSPACE
            }
        }
        val items = itemsText.lineSequence().map { it.trim().removePrefix("- ").removePrefix("• ") }
            .filter { it.isNotBlank() }.take(40).toList()
        val safeTitle = title.trim().ifBlank { "今日待办" }
        val safeDate = dateText.trim()
        val contentW = CONTENT_WIDTH
        val itemTextW = (contentW - 42).coerceAtLeast(80)
        val titleLayout = StaticLayout.Builder.obtain(safeTitle, 0, safeTitle.length, titlePaint, contentW)
            .setAlignment(titleAlign).setIncludePad(true).build()
        val dateLayout = if (showDate && safeDate.isNotBlank()) {
            StaticLayout.Builder.obtain(safeDate, 0, safeDate.length, datePaint, contentW)
                .setAlignment(titleAlign).setIncludePad(true).build()
        } else null
        val itemLayouts = items.map { item ->
            StaticLayout.Builder.obtain(item, 0, item.length, itemPaint, itemTextW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(true)
                .setLineSpacing(0f, when (preset) {
                    TodoPreset.CLEAN -> 1.08f
                    TodoPreset.COMPACT -> 1.0f
                    TodoPreset.FOCUS -> 1.12f
                })
                .build()
        }
        val headerGap = if (dateLayout != null) 5 else 0
        val dividerGap = if (preset == TodoPreset.COMPACT) 10 else 15
        val rowsHeight = itemLayouts.sumOf { maxOf(it.height, checkboxSize.toInt() + 4) + gap }
        val emptyHint = if (items.isEmpty()) 54 else 0
        val h = (EDGE_MARGIN * 2 + titleLayout.height + headerGap + (dateLayout?.height ?: 0) + dividerGap + 1 + 14 + rowsHeight + emptyHint)
            .coerceIn(80, MAX_HEIGHT)
        return Bitmap.createBitmap(Protocol.HEAD_DOTS, h, Bitmap.Config.ARGB_8888).also { out ->
            val c = Canvas(out)
            c.drawColor(Color.WHITE)
            c.save()
            c.translate(EDGE_MARGIN.toFloat(), EDGE_MARGIN.toFloat())
            titleLayout.draw(c)
            var y = titleLayout.height.toFloat()
            dateLayout?.let { layout ->
                y += headerGap
                c.save(); c.translate(0f, y); layout.draw(c); c.restore()
                y += layout.height
            }
            y += dividerGap
            val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = if (preset == TodoPreset.FOCUS) 2f else 1.4f }
            c.drawLine(0f, y, contentW.toFloat(), y, divider)
            y += 14f

            if (items.isEmpty()) {
                itemPaint.color = Color.GRAY
                c.drawText("写下今天要做的事", 0f, y + 32f, itemPaint)
            } else {
                val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    this.style = Paint.Style.STROKE
                    strokeWidth = if (preset == TodoPreset.FOCUS) 2.6f else 2.1f
                }
                itemLayouts.forEach { layout ->
                    val rowHeight = maxOf(layout.height.toFloat(), checkboxSize + 4f)
                    val boxTop = y + (rowHeight - checkboxSize) / 2f
                    c.drawRect(2f, boxTop, 2f + checkboxSize, boxTop + checkboxSize, boxPaint)
                    val textY = y + (rowHeight - layout.height) / 2f
                    c.save()
                    c.translate(42f, textY)
                    layout.draw(c)
                    c.restore()
                    y += rowHeight + gap
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

    /** Fit an already-corrected bitmap into the thermal-paper coordinate system. The caller keeps ownership of [src]. */
    fun preparedImage(src: Bitmap, rotationDegrees: Int = 0, scalePercent: Int = 100): Bitmap {
        val rotated = rotate(src, rotationDegrees)
        return try { fitBitmapNoRecycle(rotated, scalePercent) } finally { if (rotated !== src) rotated.recycle() }
    }

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
        val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postScale(-1f, 1f); matrix.postRotate(270f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postScale(-1f, 1f); matrix.postRotate(90f) }
            else -> return decoded
        }
        val oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (oriented !== decoded) decoded.recycle()
        return oriented
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
            var gray = FloatArray(px.size) { i ->
                val p = px[i]
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                r * 0.299f + g * 0.587f + b * 0.114f
            }
            gray = paperPreprocess(gray, w, h, a.paperPreset)
            if (a.invert) gray = FloatArray(gray.size) { 255f - gray[it] }
            when (a.mode) {
                DitherMode.THRESHOLD -> BooleanArray(gray.size) { gray[it] < a.threshold }
                else -> Ditherer.of(a.mode).dither(Contrast.adjust(gray, a.contrast), w, h)
            }
        }
        return MonoImage(h, black).trimTrailingWhite()
    }

    /**
     * Document/photo cleanup presets. The primary path deliberately uses the same long-established
     * OpenCV building blocks used by the reference scanner projects we keep for regression:
     * CLAHE, Gaussian unsharp masking, illumination flattening and adaptive Gaussian thresholding.
     * Processing runs after the source has already been fitted to printer width (384 px), so a
     * preset change is cheap enough for interactive preview.
     */
    private fun paperPreprocess(src: FloatArray, w: Int, h: Int, preset: PaperPreset): FloatArray {
        if (preset == PaperPreset.ORIGINAL) return src
        if (!PaperOpenCv.ready) return paperPreprocessFallback(src, w, h, preset)

        val gray = Mat(h, w, CvType.CV_8UC1)
        gray.put(0, 0, ByteArray(src.size) { src[it].roundToInt().coerceIn(0, 255).toByte() })
        val work = Mat()
        val background = Mat()
        val blur = Mat()
        try {
            when (preset) {
                PaperPreset.ORIGINAL -> gray.copyTo(work)
                PaperPreset.GRAYSCALE -> {
                    // Keep tonal information, but clean the paper illumination before the thermal
                    // ditherer sees it. This is useful for photos, packaging and handwriting.
                    normalizePaperLighting(gray, work, strength = 245.0)
                    if (grayContrast(work) < 46.0) {
                        val clahe = Imgproc.createCLAHE(1.25, Size(8.0, 8.0))
                        try { clahe.apply(work, work) } finally { clahe.collectGarbage() }
                    }
                }
                PaperPreset.BRIGHTEN -> {
                    // "净化" rather than a blind brightness offset: flatten warm shadows and lift
                    // the paper background while keeping gray pencil/receipt strokes.
                    normalizePaperLighting(gray, work, strength = 248.0)
                    Core.addWeighted(work, 1.03, work, 0.0, 6.0, work)
                }
                PaperPreset.SHARPEN -> {
                    normalizePaperLighting(gray, work, strength = 244.0)
                    if (grayContrast(work) < 58.0) {
                        val clahe = Imgproc.createCLAHE(1.35, Size(8.0, 8.0))
                        try { clahe.apply(work, work) } finally { clahe.collectGarbage() }
                    }
                    Imgproc.GaussianBlur(work, blur, Size(0.0, 0.0), 1.0)
                    Core.addWeighted(work, 1.62, blur, -0.62, 2.0, work)
                }
                PaperPreset.DOCUMENT -> robustDocumentBw(gray, work)
            }
            val out = ByteArray(src.size)
            work.get(0, 0, out)
            return FloatArray(out.size) { (out[it].toInt() and 0xff).toFloat() }
        } catch (_: Throwable) {
            return paperPreprocessFallback(src, w, h, preset)
        } finally {
            gray.release(); work.release(); background.release(); blur.release()
        }
    }

    /**
     * A print-oriented version of the robust document pipeline used by the reference scanner:
     * shadow division -> conditional CLAHE -> light denoise -> several threshold candidates ->
     * automatic quality selection -> conservative despeckle/border cleanup.
     *
     * The important difference from the previous implementation is that adaptive thresholding is
     * no longer always forced. Clean receipts usually prefer Otsu; uneven photographed pages often
     * prefer Gaussian/mean/Sauvola. Picking the least noisy plausible candidate avoids the black
     * pepper seen in real receipts while still rescuing pages with shadows.
     */
    private fun robustDocumentBw(gray: Mat, out: Mat) {
        val normalized = Mat()
        val denoised = Mat()
        val up = Mat()
        try {
            normalizePaperLighting(gray, normalized, strength = 246.0)
            if (grayContrast(normalized) < 44.0) {
                val clahe = Imgproc.createCLAHE(1.35, Size(8.0, 8.0))
                try { clahe.apply(normalized, normalized) } finally { clahe.collectGarbage() }
            }
            Imgproc.GaussianBlur(normalized, denoised, Size(3.0, 3.0), 0.0)

            // At printer width tiny Chinese strokes are only a few pixels. Threshold at up to 2x
            // and reduce once, which gives local threshold algorithms enough neighborhood context.
            val longSide = maxOf(denoised.cols(), denoised.rows()).coerceAtLeast(1)
            val scale = minOf(2.0, 1500.0 / longSide).coerceAtLeast(1.0)
            val thresholdInput = if (scale > 1.05) {
                Imgproc.resize(denoised, up, Size(), scale, scale, Imgproc.INTER_CUBIC)
                up
            } else denoised

            var block = (minOf(thresholdInput.cols(), thresholdInput.rows()) / 12).coerceIn(31, 81)
            if (block % 2 == 0) block++
            val c = resolveAdaptiveC(thresholdInput)

            val otsu = Mat(); val mean = Mat(); val gaussian = Mat(); val sauvola = Mat()
            try {
                Imgproc.threshold(thresholdInput, otsu, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
                Imgproc.adaptiveThreshold(thresholdInput, mean, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY, block, c)
                Imgproc.adaptiveThreshold(thresholdInput, gaussian, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, c)
                sauvolaThreshold(thresholdInput, sauvola, block, 0.28)

                val targetBlack = blackFraction(otsu).coerceIn(0.008, 0.34)
                val candidates = listOf(otsu, gaussian, mean, sauvola)
                val best = candidates.minByOrNull { scoreBwCandidate(it, targetBlack, thresholdInput) } ?: otsu
                val chosen = Mat(); best.copyTo(chosen)
                try {
                    removeTinySpeckles(chosen, if (scale > 1.05) 7 else 2)
                    reconnectThinStrokes(chosen, scale > 1.05)
                    if (scale > 1.05) {
                        Imgproc.resize(chosen, out, gray.size(), 0.0, 0.0, Imgproc.INTER_AREA)
                        Imgproc.threshold(out, out, 190.0, 255.0, Imgproc.THRESH_BINARY)
                    } else chosen.copyTo(out)
                    removeDocumentBorderArtifacts(out)
                } finally { chosen.release() }
            } finally { otsu.release(); mean.release(); gaussian.release(); sauvola.release() }
        } finally { normalized.release(); denoised.release(); up.release() }
    }

    private fun normalizePaperLighting(gray: Mat, out: Mat, strength: Double) {
        val bg = Mat()
        try {
            var k = (minOf(gray.cols(), gray.rows()) / 5).coerceIn(31, 91)
            if (k % 2 == 0) k++
            Imgproc.GaussianBlur(gray, bg, Size(k.toDouble(), k.toDouble()), 0.0)
            Core.add(bg, org.opencv.core.Scalar.all(1.0), bg)
            Core.divide(gray, bg, out, strength)
        } finally { bg.release() }
    }

    private fun grayContrast(gray: Mat): Double {
        val bytes = ByteArray((gray.total() * gray.channels()).toInt())
        gray.get(0, 0, bytes)
        if (bytes.isEmpty()) return 0.0
        var sum = 0.0; var sumSq = 0.0
        val step = (bytes.size / 12000).coerceAtLeast(1)
        var n = 0
        var i = 0
        while (i < bytes.size) {
            val v = (bytes[i].toInt() and 0xff).toDouble(); sum += v; sumSq += v * v; n++; i += step
        }
        val mean = sum / n.coerceAtLeast(1)
        return kotlin.math.sqrt((sumSq / n.coerceAtLeast(1) - mean * mean).coerceAtLeast(0.0))
    }

    private fun resolveAdaptiveC(gray: Mat): Double =
        (14.0 + (20.0 - grayContrast(gray)).coerceAtLeast(0.0) * 0.12).coerceIn(10.0, 18.0)

    private fun sauvolaThreshold(gray: Mat, out: Mat, block: Int, k: Double) {
        val f = Mat(); val mean = Mat(); val sq = Mat(); val meanSq = Mat()
        try {
            gray.convertTo(f, CvType.CV_32F)
            Imgproc.boxFilter(f, mean, CvType.CV_32F, Size(block.toDouble(), block.toDouble()))
            Core.multiply(f, f, sq)
            Imgproc.boxFilter(sq, meanSq, CvType.CV_32F, Size(block.toDouble(), block.toDouble()))
            val srcBytes = ByteArray((gray.total()).toInt()); gray.get(0, 0, srcBytes)
            val means = FloatArray(srcBytes.size); val meansSq = FloatArray(srcBytes.size)
            mean.get(0, 0, means); meanSq.get(0, 0, meansSq)
            val dst = ByteArray(srcBytes.size)
            for (i in dst.indices) {
                val m = means[i].toDouble(); val variance = (meansSq[i] - means[i] * means[i]).toDouble().coerceAtLeast(0.0)
                val std = kotlin.math.sqrt(variance)
                val threshold = m * (1.0 + k * (std / 128.0 - 1.0))
                dst[i] = if ((srcBytes[i].toInt() and 0xff) > threshold) 255.toByte() else 0
            }
            out.create(gray.rows(), gray.cols(), CvType.CV_8UC1); out.put(0, 0, dst)
        } finally { f.release(); mean.release(); sq.release(); meanSq.release() }
    }

    private fun blackFraction(bw: Mat): Double {
        val total = bw.total().toDouble().coerceAtLeast(1.0)
        return (total - Core.countNonZero(bw)) / total
    }

    private fun scoreBwCandidate(bw: Mat, targetBlack: Double, sourceGray: Mat): Double {
        val black = blackFraction(bw)
        var score = kotlin.math.abs(black - targetBlack)
        if (black < .006) score += 2.0
        if (black > .48) score += 2.0
        val inv = Mat(); val labels = Mat(); val stats = Mat(); val centroids = Mat()
        try {
            Core.bitwise_not(bw, inv)
            val n = Imgproc.connectedComponentsWithStats(inv, labels, stats, centroids, 8, CvType.CV_32S)
            if (n > 1) {
                var tiny = 0
                for (i in 1 until n) if (stats.get(i, Imgproc.CC_STAT_AREA)[0] < 5.0) tiny++
                val area = bw.total().toDouble().coerceAtLeast(1.0)
                score += tiny / (n - 1).toDouble() * 1.6 + minOf(1.4, tiny * 10000.0 / area * .065)
            }
        } catch (_: Throwable) { score += .25 }
        finally { inv.release(); labels.release(); stats.release(); centroids.release() }

        // Text/stroke retention: a candidate that looks wonderfully clean but erases the faint
        // receipt characters is not useful. Reward black pixels that still cover real grayscale
        // edges, while the component-noise term above keeps paper grain from gaming the score.
        val edges = Mat(); val blackMask = Mat(); val expanded = Mat(); val overlap = Mat()
        var kernel: Mat? = null
        try {
            Imgproc.Canny(sourceGray, edges, 35.0, 105.0)
            Core.bitwise_not(bw, blackMask)
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.dilate(blackMask, expanded, kernel)
            Core.bitwise_and(edges, expanded, overlap)
            val edgeCount = Core.countNonZero(edges).toDouble()
            if (edgeCount > 20.0) {
                val recall = Core.countNonZero(overlap) / edgeCount
                if (recall < .72) score += (.72 - recall) * .9
                else score -= minOf(.12, (recall - .72) * .7)
            }
        } catch (_: Throwable) { }
        finally { edges.release(); blackMask.release(); expanded.release(); overlap.release(); kernel?.release() }
        return score
    }

    private fun removeTinySpeckles(bw: Mat, minArea: Int) {
        val inv = Mat(); val labels = Mat(); val stats = Mat(); val centroids = Mat(); val mask = Mat()
        try {
            Core.bitwise_not(bw, inv)
            val n = Imgproc.connectedComponentsWithStats(inv, labels, stats, centroids, 8, CvType.CV_32S)
            for (i in 1 until n) {
                if (stats.get(i, Imgproc.CC_STAT_AREA)[0] < minArea) {
                    Core.compare(labels, org.opencv.core.Scalar(i.toDouble()), mask, Core.CMP_EQ)
                    bw.setTo(org.opencv.core.Scalar(255.0), mask)
                }
            }
        } catch (_: Throwable) {}
        finally { inv.release(); labels.release(); stats.release(); centroids.release(); mask.release() }
    }

    private fun reconnectThinStrokes(bw: Mat, highRes: Boolean) {
        if (!highRes) return
        val inv = Mat(); val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        try {
            Core.bitwise_not(bw, inv)
            Imgproc.morphologyEx(inv, inv, Imgproc.MORPH_CLOSE, kernel)
            Core.bitwise_not(inv, bw)
        } catch (_: Throwable) {}
        finally { inv.release(); kernel.release() }
    }

    private fun removeDocumentBorderArtifacts(binary: Mat) {
        val inv = Mat()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val mask = Mat()
        try {
            Core.bitwise_not(binary, inv)
            val count = Imgproc.connectedComponentsWithStats(inv, labels, stats, centroids, 8, CvType.CV_32S)
            val w = binary.cols()
            val h = binary.rows()
            val total = (w * h).coerceAtLeast(1)
            for (label in 1 until count) {
                val left = stats.get(label, Imgproc.CC_STAT_LEFT)[0].toInt()
                val top = stats.get(label, Imgproc.CC_STAT_TOP)[0].toInt()
                val cw = stats.get(label, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val ch = stats.get(label, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val area = stats.get(label, Imgproc.CC_STAT_AREA)[0].toInt()
                val touches = left <= 1 || top <= 1 || left + cw >= w - 1 || top + ch >= h - 1
                val borderLike = cw >= (w * .42f) || ch >= (h * .42f) || area >= (total * .012f)
                // Do not erase a page that is genuinely mostly black.
                if (touches && borderLike && area < total * .28f) {
                    Core.compare(labels, org.opencv.core.Scalar(label.toDouble()), mask, Core.CMP_EQ)
                    binary.setTo(org.opencv.core.Scalar(255.0), mask)
                }
            }
            // A white one-pixel safety rim prevents interpolation residue from reappearing as a
            // solid thermal-print border.
            Imgproc.rectangle(binary, org.opencv.core.Point(0.0, 0.0), org.opencv.core.Point((w - 1).toDouble(), (h - 1).toDouble()), org.opencv.core.Scalar(255.0), 1)
        } catch (_: Throwable) {
            // Border cleanup is optional; never make a usable preset fail because of it.
        } finally {
            inv.release(); labels.release(); stats.release(); centroids.release(); mask.release()
        }
    }

    /** Pure-Kotlin fallback used only if the native OpenCV runtime cannot be initialized. */
    private fun paperPreprocessFallback(src: FloatArray, w: Int, h: Int, preset: PaperPreset): FloatArray {
        if (preset == PaperPreset.ORIGINAL) return src
        if (preset == PaperPreset.GRAYSCALE) {
            val local = run {
                val stride = w + 1
                val integral = DoubleArray((w + 1) * (h + 1))
                for (y in 0 until h) { var row = 0.0; for (x in 0 until w) { row += src[y*w+x]; integral[(y+1)*stride+x+1] = integral[y*stride+x+1] + row } }
                FloatArray(src.size) { i ->
                    val y=i/w; val x=i%w; val r=4; val x0=(x-r).coerceAtLeast(0); val x1=(x+r+1).coerceAtMost(w); val y0=(y-r).coerceAtLeast(0); val y1=(y+r+1).coerceAtMost(h)
                    val sum=integral[y1*stride+x1]-integral[y0*stride+x1]-integral[y1*stride+x0]+integral[y0*stride+x0]
                    (sum/((x1-x0)*(y1-y0))).toFloat()
                }
            }
            return FloatArray(src.size) { (128f + (src[it] - local[it]) * 1.08f + (local[it]-128f)*0.92f).coerceIn(0f,255f) }
        }
        if (preset == PaperPreset.BRIGHTEN) return FloatArray(src.size) { (src[it] * 1.06f + 16f).coerceIn(0f, 255f) }

        fun boxMean(radius: Int): FloatArray {
            val stride = w + 1
            val integral = DoubleArray((w + 1) * (h + 1))
            for (y in 0 until h) {
                var row = 0.0
                for (x in 0 until w) {
                    row += src[y * w + x]
                    integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + row
                }
            }
            val out = FloatArray(src.size)
            for (y in 0 until h) {
                val y0 = (y - radius).coerceAtLeast(0)
                val y1 = (y + radius + 1).coerceAtMost(h)
                for (x in 0 until w) {
                    val x0 = (x - radius).coerceAtLeast(0)
                    val x1 = (x + radius + 1).coerceAtMost(w)
                    val sum = integral[y1 * stride + x1] - integral[y0 * stride + x1] - integral[y1 * stride + x0] + integral[y0 * stride + x0]
                    out[y * w + x] = (sum / ((x1 - x0) * (y1 - y0))).toFloat()
                }
            }
            return out
        }
        return when (preset) {
            PaperPreset.SHARPEN -> {
                val blur = boxMean(1)
                FloatArray(src.size) { (src[it] + 0.75f * (src[it] - blur[it]) + 6f).coerceIn(0f, 255f) }
            }
            PaperPreset.DOCUMENT -> {
                val local = boxMean(12)
                FloatArray(src.size) { if (src[it] < local[it] - 10f) 0f else 255f }
            }
            else -> src
        }
    }

    private object PaperOpenCv {
        val ready: Boolean by lazy { runCatching { OpenCVLoader.initLocal() }.getOrDefault(false) }
    }

}
