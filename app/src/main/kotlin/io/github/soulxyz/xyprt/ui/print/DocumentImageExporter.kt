package io.github.soulxyz.xyprt.ui.print

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.soulxyz.xyprt.model.LabelElement
import io.github.soulxyz.xyprt.model.LabelTemplate
import io.github.soulxyz.xyprt.render.LabelRenderer
import io.github.soulxyz.xyprt.model.LabelSpec
import java.io.File

object DocumentImageExporter {
    /**
     * High-quality document image. Vector/text/freehand elements stay vector until this final render.
     * Export is 3× printer resolution so shared handwriting/text does not look like a 384 px screenshot.
     */
    fun render(template: LabelTemplate, resolved: List<LabelElement>, outputHeight: Int? = null): Bitmap {
        val logicalHeight = outputHeight?.coerceIn(1, template.spec.lengthPx) ?: template.spec.lengthPx
        // Normal documents export at 3× printer resolution. Very long pages use 2× to avoid
        // allocating a 50+ MiB bitmap while still keeping a much sharper share image than 384 px.
        val scale = if (logicalHeight > 2_000) 2 else 3
        val bitmap = Bitmap.createBitmap(
            LabelSpec.PRINT_WIDTH_PX * scale,
            logicalHeight * scale,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.scale(scale.toFloat(), scale.toFloat())
        canvas.clipRect(0f, 0f, LabelSpec.PRINT_WIDTH_PX.toFloat(), logicalHeight.toFloat())
        LabelRenderer.drawInto(canvas, template.spec, LabelRenderer.reanchor(template.elements, resolved))
        return bitmap
    }

    fun saveToUri(context: Context, uri: Uri, bitmap: Bitmap) {
        context.contentResolver.openOutputStream(uri, "w")?.use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "保存图片失败" }
        } ?: error("无法写入所选位置")
    }

    fun share(context: Context, bitmap: Bitmap, fileName: String) {
        val dir = File(context.cacheDir, "shared_exports").apply { mkdirs() }
        val safe = fileName.replace(Regex("[\\/:*?\"<>|]+"), "_").ifBlank { "错题小印" }
        val file = File(dir, "${safe}_${System.currentTimeMillis()}.png")
        file.outputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "生成分享图片失败" }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享图片"))
    }
}
