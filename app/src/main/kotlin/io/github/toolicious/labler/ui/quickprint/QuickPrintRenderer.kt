package io.github.toolicious.labler.ui.quickprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.github.toolicious.labler.printer.MonoImage
import io.github.toolicious.labler.printer.Protocol
import io.github.toolicious.labler.printer.dither.DitherMode
import io.github.toolicious.labler.printer.dither.Ditherer
import kotlin.math.roundToInt

/** Render arbitrary shared content into the BY-288's normal portrait paper coordinate system. */
object QuickPrintRenderer {
    private const val MARGIN = 16
    private const val CONTENT_WIDTH = Protocol.HEAD_DOTS - MARGIN * 2
    private const val MAX_HEIGHT = 60_000

    fun text(text: String, fontPx: Float = 30f): Bitmap {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = fontPx
        }
        val body = text.ifBlank { "请输入要打印的文字" }
        val layout = StaticLayout.Builder.obtain(body, 0, body.length, paint, CONTENT_WIDTH)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(true)
            .setLineSpacing(2f, 1f)
            .build()
        val h = (layout.height + MARGIN * 2).coerceIn(64, MAX_HEIGHT)
        return Bitmap.createBitmap(Protocol.HEAD_DOTS, h, Bitmap.Config.ARGB_8888).also { out ->
            Canvas(out).apply {
                drawColor(Color.WHITE)
                save()
                translate(MARGIN.toFloat(), MARGIN.toFloat())
                layout.draw(this)
                restore()
            }
        }
    }

    fun image(context: Context, uri: Uri): Bitmap =
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        }?.let(::fitBitmap) ?: error("无法读取图片")

    fun images(context: Context, uris: List<Uri>): Bitmap {
        val pages = uris.take(20).map { image(context, it) }
        return stack(pages)
    }

    fun pdf(context: Context, uri: Uri): Bitmap {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("无法打开 PDF")
        val rendered = mutableListOf<Bitmap>()
        PdfRenderer(pfd).use { pdf ->
            var used = 0
            for (i in 0 until minOf(pdf.pageCount, 20)) {
                pdf.openPage(i).use { page ->
                    val targetW = CONTENT_WIDTH
                    val targetH = (page.height * (targetW / page.width.toFloat())).roundToInt().coerceAtLeast(1)
                    if (used + targetH + MARGIN * 2 > MAX_HEIGHT) return@use
                    val pageBmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                    Canvas(pageBmp).drawColor(Color.WHITE)
                    page.render(pageBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    rendered += pageBmp
                    used += targetH + MARGIN
                }
                if (used >= MAX_HEIGHT - 1024) break
            }
        }
        pfd.close()
        if (rendered.isEmpty()) error("PDF 没有可打印页面")
        return stack(rendered)
    }

    /** Fit one image to the printable width without rotating it. */
    private fun fitBitmap(src: Bitmap): Bitmap {
        val scale = CONTENT_WIDTH / src.width.toFloat().coerceAtLeast(1f)
        val h = (src.height * scale).roundToInt().coerceIn(1, MAX_HEIGHT - MARGIN * 2)
        val scaled = Bitmap.createScaledBitmap(src, CONTENT_WIDTH, h, true)
        val out = Bitmap.createBitmap(Protocol.HEAD_DOTS, h + MARGIN * 2, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(scaled, null, Rect(MARGIN, MARGIN, MARGIN + CONTENT_WIDTH, MARGIN + h), null)
        }
        if (scaled !== src) scaled.recycle()
        src.recycle()
        return out
    }

    private fun stack(parts: List<Bitmap>): Bitmap {
        if (parts.size == 1 && parts[0].width == Protocol.HEAD_DOTS) return parts[0]
        val heights = parts.map { p ->
            if (p.width == Protocol.HEAD_DOTS) p.height
            else (p.height * (CONTENT_WIDTH / p.width.toFloat())).roundToInt() + MARGIN * 2
        }
        val total = (heights.sum() + MARGIN * (parts.size - 1)).coerceAtMost(MAX_HEIGHT)
        val out = Bitmap.createBitmap(Protocol.HEAD_DOTS, total.coerceAtLeast(64), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        var y = 0
        parts.forEachIndexed { index, p ->
            if (y >= total) return@forEachIndexed
            if (p.width == Protocol.HEAD_DOTS) {
                c.drawBitmap(p, 0f, y.toFloat(), null)
                y += p.height
            } else {
                val h = (p.height * (CONTENT_WIDTH / p.width.toFloat())).roundToInt()
                val bottom = minOf(total, y + MARGIN + h)
                if (bottom > y + MARGIN) c.drawBitmap(p, null, Rect(MARGIN, y + MARGIN, MARGIN + CONTENT_WIDTH, bottom), null)
                y += h + MARGIN * 2
            }
            if (index != parts.lastIndex) y += MARGIN
            p.recycle()
        }
        return out
    }

    fun toMono(bitmap: Bitmap, mode: DitherMode): MonoImage {
        require(bitmap.width == Protocol.HEAD_DOTS)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val gray = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            gray[i] = (r * 0.299f + g * 0.587f + b * 0.114f)
        }
        return MonoImage(bitmap.height, Ditherer.of(mode).dither(gray, bitmap.width, bitmap.height)).trimTrailingWhite()
    }
}
