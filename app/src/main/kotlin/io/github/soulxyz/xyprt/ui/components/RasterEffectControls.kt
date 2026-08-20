package io.github.soulxyz.xyprt.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
@OptIn(ExperimentalMaterial3Api::class)
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
    removeRedInk: Boolean? = null,
    removeBlueInk: Boolean? = null,
    onRemoveRedInk: ((Boolean) -> Unit)? = null,
    onRemoveBlueInk: ((Boolean) -> Unit)? = null,
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
            removeRedInk = removeRedInk,
            removeBlueInk = removeBlueInk,
            onRemoveRedInk = onRemoveRedInk,
            onRemoveBlueInk = onRemoveBlueInk,
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
    removeRedInk: Boolean? = null,
    removeBlueInk: Boolean? = null,
    onRemoveRedInk: ((Boolean) -> Unit)? = null,
    onRemoveBlueInk: ((Boolean) -> Unit)? = null,
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


        if (removeRedInk != null && removeBlueInk != null && onRemoveRedInk != null && onRemoveBlueInk != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("彩色笔迹", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(72.dp))
                FilterChip(
                    selected = removeRedInk,
                    onClick = { onRemoveRedInk(!removeRedInk) },
                    label = { Text("去红笔") },
                )
                FilterChip(
                    selected = removeBlueInk,
                    onClick = { onRemoveBlueInk(!removeBlueInk) },
                    label = { Text("去蓝笔") },
                )
            }
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

private enum class RasterAdjustmentTab(val label: String) { EFFECT("效果"), OUTLINE("线稿"), LAYOUT("布局"), SMART("智能") }

/**
 * Tabbed version of the adjustment panel. Different parameter kinds are grouped so a long
 * "slider scroll" never hides the preview. The quick-preset thumbnails live outside this panel.
 * Optional layout/smart groups are hidden when their values are not supplied (e.g. the editor).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RasterAdjustmentTabs(
    mode: DitherMode,
    threshold: Int,
    contrast: Int,
    invert: Boolean,
    onMode: ((DitherMode) -> Unit)? = null,
    onThreshold: (Int) -> Unit,
    onContrast: (Int) -> Unit,
    onInvert: (Boolean) -> Unit,
    outlineSensitivity: Int,
    outlineThickness: Int,
    outlineMethod: OutlineMethod,
    outlineSmooth: Boolean,
    onOutlineSensitivity: (Int) -> Unit,
    onOutlineThickness: (Int) -> Unit,
    onOutlineMethod: (OutlineMethod) -> Unit,
    onOutlineSmooth: (Boolean) -> Unit,
    rotationDegrees: Int? = null,
    onRotationDegrees: ((Int) -> Unit)? = null,
    scalePercent: Int? = null,
    onScalePercent: ((Int) -> Unit)? = null,
    landscapePrint: Boolean? = null,
    onLandscapePrint: ((Boolean) -> Unit)? = null,
    enhance: Boolean? = null,
    onEnhance: ((Boolean) -> Unit)? = null,
    removeRedInk: Boolean? = null,
    removeBlueInk: Boolean? = null,
    onRemoveRedInk: ((Boolean) -> Unit)? = null,
    onRemoveBlueInk: ((Boolean) -> Unit)? = null,
) {
    val showLayout = rotationDegrees != null || scalePercent != null || landscapePrint != null
    val showSmart = enhance != null || removeRedInk != null || removeBlueInk != null
    val visibleTabs = buildList {
        add(RasterAdjustmentTab.EFFECT)
        add(RasterAdjustmentTab.OUTLINE)
        if (showLayout) add(RasterAdjustmentTab.LAYOUT)
        if (showSmart) add(RasterAdjustmentTab.SMART)
    }
    var tab by remember { mutableStateOf(RasterAdjustmentTab.EFFECT) }
    val safeTab = if (tab in visibleTabs) tab else visibleTabs.first()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryTabRow(selectedTabIndex = visibleTabs.indexOf(safeTab)) {
            visibleTabs.forEach { t ->
                Tab(selected = safeTab == t, onClick = { tab = t }, text = { Text(t.label) })
            }
        }
        when (safeTab) {
            RasterAdjustmentTab.EFFECT -> {
                if (onMode != null) {
                    RasterModeSelector(mode = mode, onMode = onMode)
                }
                when (mode) {
                    DitherMode.OUTLINE -> {}
                    DitherMode.THRESHOLD -> CompactSliderLine(
                        label = "黑白 $threshold",
                        value = threshold.toFloat(),
                        valueRange = 20f..235f,
                        onValueChange = { onThreshold(it.roundToInt()) },
                        trailing = InvertTrailing(invert, onInvert),
                    )
                    DitherMode.FLOYD_STEINBERG, DitherMode.ATKINSON -> CompactSliderLine(
                        label = "对比 $contrast",
                        value = contrast.toFloat(),
                        valueRange = -100f..100f,
                        onValueChange = { onContrast(it.roundToInt()) },
                        trailing = InvertTrailing(invert, onInvert),
                    )
                }
            }
            RasterAdjustmentTab.OUTLINE -> {
                CompactSliderLine(
                    label = "细节 $outlineSensitivity",
                    value = outlineSensitivity.toFloat(),
                    valueRange = 0f..100f,
                    onValueChange = { onOutlineSensitivity(it.roundToInt()) },
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
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("平滑", style = MaterialTheme.typography.labelMedium)
                    Switch(checked = outlineSmooth, onCheckedChange = onOutlineSmooth)
                    Spacer(Modifier.weight(1f))
                    Text("反色", style = MaterialTheme.typography.labelMedium)
                    Switch(checked = invert, onCheckedChange = onInvert)
                }
            }
            RasterAdjustmentTab.LAYOUT -> {
                if (rotationDegrees != null && onRotationDegrees != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                if (landscapePrint != null && onLandscapePrint != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("横向打印", style = MaterialTheme.typography.labelMedium)
                        Switch(checked = landscapePrint, onCheckedChange = onLandscapePrint)
                    }
                }
            }
            RasterAdjustmentTab.SMART -> {
                if (enhance != null && onEnhance != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("AI 增强", style = MaterialTheme.typography.labelLarge)
                            Text(
                                "去阴影、提白并校正偏色纸张，需要下载增强与黑位模型",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = enhance, onCheckedChange = onEnhance)
                    }
                }
                if (removeRedInk != null && removeBlueInk != null && onRemoveRedInk != null && onRemoveBlueInk != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("彩色笔迹", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(72.dp))
                        FilterChip(
                            selected = removeRedInk,
                            onClick = { onRemoveRedInk(!removeRedInk) },
                            label = { Text("去红笔") },
                        )
                        FilterChip(
                            selected = removeBlueInk,
                            onClick = { onRemoveBlueInk(!removeBlueInk) },
                            label = { Text("去蓝笔") },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InvertTrailing(invert: Boolean, onInvert: (Boolean) -> Unit): @Composable RowScope.() -> Unit = {
    Text("反色", style = MaterialTheme.typography.labelMedium)
    Switch(checked = invert, onCheckedChange = onInvert)
}
