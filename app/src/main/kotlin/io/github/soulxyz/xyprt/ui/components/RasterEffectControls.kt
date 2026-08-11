package io.github.soulxyz.xyprt.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.soulxyz.xyprt.printer.dither.DitherMode
import io.github.soulxyz.xyprt.printer.dither.OutlineMethod
import kotlin.math.roundToInt

/**
 * Shared print-image controls.
 *
 * The editor and quick-print flow deliberately use the same labels, value ranges and control
 * implementation. Quick print may place [RasterAdjustmentDetails] in a bottom sheet so the
 * preview stays visible; the editor can keep the same details inline next to the selected image.
 */
@Composable
fun RasterEffectControls(
    mode: DitherMode,
    threshold: Int,
    contrast: Int,
    invert: Boolean,
    outlineSensitivity: Int,
    outlineThickness: Int,
    outlineMethod: OutlineMethod,
    outlineSmooth: Boolean,
    onMode: (DitherMode) -> Unit,
    onThreshold: (Int) -> Unit,
    onContrast: (Int) -> Unit,
    onInvert: (Boolean) -> Unit,
    onOutlineSensitivity: (Int) -> Unit,
    onOutlineThickness: (Int) -> Unit,
    onOutlineMethod: (OutlineMethod) -> Unit,
    onOutlineSmooth: (Boolean) -> Unit,
    rotationDegrees: Int? = null,
    onRotationDegrees: ((Int) -> Unit)? = null,
    scalePercent: Int? = null,
    onScalePercent: ((Int) -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RasterModeSelector(mode = mode, onMode = onMode)
        RasterAdjustmentDetails(
            mode = mode,
            threshold = threshold,
            contrast = contrast,
            invert = invert,
            outlineSensitivity = outlineSensitivity,
            outlineThickness = outlineThickness,
            outlineMethod = outlineMethod,
            outlineSmooth = outlineSmooth,
            onThreshold = onThreshold,
            onContrast = onContrast,
            onInvert = onInvert,
            onOutlineSensitivity = onOutlineSensitivity,
            onOutlineThickness = onOutlineThickness,
            onOutlineMethod = onOutlineMethod,
            onOutlineSmooth = onOutlineSmooth,
            rotationDegrees = rotationDegrees,
            onRotationDegrees = onRotationDegrees,
            scalePercent = scalePercent,
            onScalePercent = onScalePercent,
        )
    }
}

@Composable
fun RasterModeSelector(
    mode: DitherMode,
    onMode: (DitherMode) -> Unit,
    showLabel: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showLabel) {
            Text(
                "效果",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(40.dp),
                maxLines = 1,
            )
        }
        listOf(
            DitherMode.OUTLINE to "线稿",
            DitherMode.THRESHOLD to "黑白",
            DitherMode.FLOYD_STEINBERG to "细腻",
            DitherMode.ATKINSON to "清晰",
        ).forEach { (item, label) ->
            FilterChip(
                selected = mode == item,
                onClick = { onMode(item) },
                label = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun RasterAdjustmentDetails(
    mode: DitherMode,
    threshold: Int,
    contrast: Int,
    invert: Boolean,
    outlineSensitivity: Int,
    outlineThickness: Int,
    outlineMethod: OutlineMethod,
    outlineSmooth: Boolean,
    onThreshold: (Int) -> Unit,
    onContrast: (Int) -> Unit,
    onInvert: (Boolean) -> Unit,
    onOutlineSensitivity: (Int) -> Unit,
    onOutlineThickness: (Int) -> Unit,
    onOutlineMethod: (OutlineMethod) -> Unit,
    onOutlineSmooth: (Boolean) -> Unit,
    rotationDegrees: Int? = null,
    onRotationDegrees: ((Int) -> Unit)? = null,
    scalePercent: Int? = null,
    onScalePercent: ((Int) -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (mode) {
            DitherMode.OUTLINE -> {
                CompactSliderLine(
                    label = "细节 $outlineSensitivity",
                    value = outlineSensitivity.toFloat(),
                    valueRange = 0f..100f,
                    onValueChange = { onOutlineSensitivity(it.roundToInt()) },
                    trailing = {
                        Text("反色", style = MaterialTheme.typography.labelMedium)
                        Switch(checked = invert, onCheckedChange = onInvert)
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("线宽", style = MaterialTheme.typography.labelMedium)
                    (1..3).forEach { n ->
                        FilterChip(
                            selected = outlineThickness == n,
                            onClick = { onOutlineThickness(n) },
                            label = { Text(n.toString()) },
                        )
                    }
                    FilterChip(
                        selected = outlineMethod == OutlineMethod.CANNY,
                        onClick = { onOutlineMethod(OutlineMethod.CANNY) },
                        label = { Text("边缘", maxLines = 1) },
                    )
                    FilterChip(
                        selected = outlineMethod == OutlineMethod.LINES,
                        onClick = { onOutlineMethod(OutlineMethod.LINES) },
                        label = { Text("图形", maxLines = 1) },
                    )
                    Spacer(Modifier.weight(1f))
                    Text("平滑", style = MaterialTheme.typography.labelMedium)
                    Switch(checked = outlineSmooth, onCheckedChange = onOutlineSmooth)
                }
            }
            DitherMode.THRESHOLD -> CompactSliderLine(
                label = "黑白 $threshold",
                value = threshold.toFloat(),
                valueRange = 20f..235f,
                onValueChange = { onThreshold(it.roundToInt()) },
                trailing = {
                    Text("反色", style = MaterialTheme.typography.labelMedium)
                    Switch(checked = invert, onCheckedChange = onInvert)
                },
            )
            DitherMode.FLOYD_STEINBERG, DitherMode.ATKINSON -> CompactSliderLine(
                label = "对比 $contrast",
                value = contrast.toFloat(),
                valueRange = -100f..100f,
                onValueChange = { onContrast(it.roundToInt()) },
                trailing = {
                    Text("反色", style = MaterialTheme.typography.labelMedium)
                    Switch(checked = invert, onCheckedChange = onInvert)
                },
            )
        }

        if (rotationDegrees != null && onRotationDegrees != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("旋转", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(44.dp))
                listOf(0, 90, 180, 270).forEach { deg ->
                    FilterChip(
                        selected = rotationDegrees == deg,
                        onClick = { onRotationDegrees(deg) },
                        label = { Text("${deg}°", maxLines = 1) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (scalePercent != null && onScalePercent != null) {
            CompactSliderLine(
                label = "缩放 $scalePercent%",
                value = scalePercent.toFloat(),
                valueRange = 50f..180f,
                onValueChange = { onScalePercent(it.roundToInt()) },
            )
        }
    }
}

@Composable
private fun CompactSliderLine(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(88.dp), maxLines = 1)
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}
