package io.github.toolicious.labler.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.toolicious.labler.printer.MonoImage
import io.github.toolicious.labler.render.MonoConverter

/**
 * Width-first preview for continuous paper.
 *
 * A very long screenshot/document must NOT be fitted entirely into the viewport,
 * otherwise its 384-dot paper width collapses to a tiny stripe.  The paper is
 * always drawn at full available width and the viewport scrolls vertically.
 */
@Composable
fun MonoPaperPreview(
    image: MonoImage,
    modifier: Modifier = Modifier,
    minViewportHeight: Dp = 180.dp,
    maxViewportHeight: Dp = 480.dp,
) {
    val bitmap = remember(image) { MonoConverter.toBitmap(image).asImageBitmap() }
    val scroll = rememberScrollState()
    val ratio = image.width.toFloat() / image.height.coerceAtLeast(1).toFloat()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minViewportHeight, max = maxViewportHeight)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
            .verticalScroll(scroll),
        contentAlignment = Alignment.TopCenter,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio),
            contentScale = ContentScale.FillBounds,
            filterQuality = FilterQuality.None,
        )
    }
}
