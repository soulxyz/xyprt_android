package io.github.soulxyz.xyprt.render

import android.graphics.Bitmap
import io.github.soulxyz.xyprt.printer.MonoImage
import io.github.soulxyz.xyprt.printer.Protocol

/** Converts a normal portrait 384-dot-wide bitmap into the printer's 1-bit image. */
object MonoConverter {

    fun toBitmap(mono: MonoImage): Bitmap {
        val px = IntArray(mono.width * mono.height) { i ->
            if (mono.black[i]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        return Bitmap.createBitmap(px, mono.width, mono.height, Bitmap.Config.ARGB_8888)
    }

    fun convert(bitmap: Bitmap): MonoImage {
        require(bitmap.width == Protocol.HEAD_DOTS) {
            "Bitmap width must be ${Protocol.HEAD_DOTS} px, was ${bitmap.width}"
        }
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val black = BooleanArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            black[i] = (r + g + b) / 3 < 128
        }
        return MonoImage(h, black)
    }
}
