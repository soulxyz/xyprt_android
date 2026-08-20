package io.github.soulxyz.xyprt.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.soulxyz.xyprt.printer.MonoImage
import io.github.soulxyz.xyprt.render.MonoConverter
import io.github.soulxyz.xyprt.ui.quickprint.PaperPreset

/** One effect preset shown as a real preview image with a small label underneath. */
data class EffectThumb(
    val id: String,
    val label: String,
    val mono: MonoImage?,
    val error: String? = null,
)

/**
 * Generic row of effect presets, each drawn as its own thumbnail with a small label underneath.
 * The user gets an impression of every effect without switching between preset tabs. Falls back
 * to a placeholder tile while a thumbnail is not available yet.
 */
@Composable
fun EffectThumbRow(
    selectedId: String,
    thumbs: List<EffectThumb>,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        thumbs.forEach { thumb ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(72.dp),
            ) {
                Surface(
                    onClick = { onSelect(thumb.id) },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface,
                    border = if (selectedId == thumb.id) {
                        androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    },
                ) {
                    if (thumb.mono != null) {
                        val bitmap = remember(thumb.mono) { MonoConverter.toBitmap(thumb.mono).asImageBitmap() }
                        Image(
                            bitmap = bitmap,
                            contentDescription = thumb.label,
                            modifier = Modifier
                                .width(72.dp)
                                .height(56.dp),
                            contentScale = ContentScale.Fit,
                            filterQuality = FilterQuality.None,
                        )
                    } else if (thumb.error != null) {
                        Box(
                            modifier = Modifier
                                .width(72.dp)
                                .height(56.dp)
                                .background(MaterialTheme.colorScheme.errorContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "⚠",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .width(72.dp)
                                .height(56.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerLow),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(
                    thumb.label,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                    fontWeight = if (selectedId == thumb.id) FontWeight.SemiBold else null,
                )
            }
        }
    }
}

private fun paperPresetLabel(preset: PaperPreset): String = when (preset) {
    PaperPreset.ORIGINAL -> "原图"
    PaperPreset.BRIGHTEN -> "净化"
    PaperPreset.SHARPEN -> "清晰"
    PaperPreset.DOCUMENT -> "黑白文档"
    PaperPreset.GRAYSCALE -> "灰度"
}

/** Paper-preprocess quick presets as real thumbnails with labels. */
@Composable
fun PaperPresetThumbRow(
    selected: PaperPreset,
    thumbs: Map<PaperPreset, MonoImage?>,
    onSelect: (PaperPreset) -> Unit,
) {
    EffectThumbRow(
        selectedId = selected.name,
        thumbs = PaperPreset.entries.map { preset -> EffectThumb(preset.name, paperPresetLabel(preset), thumbs[preset]) },
        onSelect = { id -> PaperPreset.entries.firstOrNull { it.name == id }?.let(onSelect) },
    )
}
