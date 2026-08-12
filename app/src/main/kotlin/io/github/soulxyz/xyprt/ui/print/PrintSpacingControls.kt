package io.github.soulxyz.xyprt.ui.print

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.soulxyz.xyprt.printer.Protocol
import java.util.Locale
import kotlin.math.roundToInt

/** Small, user-facing control for the blank paper before/after content. */
@Composable
fun PrintSpacingControls(
    beforeDots: Int,
    afterDots: Int,
    enabled: Boolean,
    onBeforeDots: (Int) -> Unit,
    onAfterDots: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("前留白 ${dotsMm(beforeDots)} mm", style = MaterialTheme.typography.labelMedium)
            Text("后留白 ${dotsMm(afterDots)} mm", style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = beforeDots.toFloat(),
            onValueChange = { onBeforeDots(it.roundToInt()) },
            valueRange = 0f..80f,
            enabled = enabled,
        )
        Slider(
            value = afterDots.toFloat(),
            onValueChange = { onAfterDots(it.roundToInt()) },
            valueRange = 0f..240f,
            enabled = enabled,
        )
    }
}

private fun dotsMm(dots: Int): String = String.format(
    Locale.SIMPLIFIED_CHINESE,
    "%.1f",
    dots.toFloat() / Protocol.DOTS_PER_MM,
)
