package io.github.soulxyz.xyprt.ui.print

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.soulxyz.xyprt.R
import io.github.soulxyz.xyprt.ble.PrinterState
import io.github.soulxyz.xyprt.ble.PrinterTransport
import io.github.soulxyz.xyprt.printer.MediaType
import io.github.soulxyz.xyprt.printer.MonoImage
import io.github.soulxyz.xyprt.ui.components.MonoPaperPreview

/**
 * Print dialog with pixel-exact 1-bit preview (exactly what the printer
 * receives), copy count and paper-type toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintSheet(
    image: MonoImage,
    initialMedia: MediaType,
    onDismiss: () -> Unit,
    onPrinted: (copies: Int, media: MediaType, feedBeforeDots: Int, feedAfterDots: Int) -> Unit = { _, _, _, _ -> },
    onOpenPrinterSettings: () -> Unit = {},
    vm: PrintViewModel = viewModel(),
) {
    val working by vm.working.collectAsState()
    val error by vm.error.collectAsState()
    val done by vm.done.collectAsState()
    val printerState by vm.printerState.collectAsState()
    val savedPrinter by vm.savedPrinter.collectAsState(initial = null)
    val savedBefore by vm.feedBeforeDots.collectAsState(initial = io.github.soulxyz.xyprt.printer.Protocol.PRE_FEED_DOTS)
    val savedAfter by vm.feedAfterDots.collectAsState(initial = io.github.soulxyz.xyprt.printer.Protocol.CONTINUOUS_FEED_DOTS)

    var copies by remember { mutableIntStateOf(1) }
    var feedBefore by remember(savedBefore) { mutableIntStateOf(savedBefore) }
    var feedAfter by remember(savedAfter) { mutableIntStateOf(savedAfter) }
    val media = MediaType.CONTINUOUS // BY-288 normal workflow: continuous roll; no gap calibration needed.

    val view = LocalView.current
    DisposableEffect(working) {
        view.keepScreenOn = working
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(done) {
        if (done) {
            onPrinted(copies, media, feedBefore, feedAfter)
            vm.reset()
            onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = { if (!working) onDismiss() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .navigationBarsPadding(),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Text(stringResource(R.string.action_print), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))

                Text(
                    stringResource(R.string.print_preview, image.height / 8),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(4.dp))
                MonoPaperPreview(
                    image = image,
                    minViewportHeight = 180.dp,
                    maxViewportHeight = 420.dp,
                )
                Spacer(Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.print_copies), style = MaterialTheme.typography.bodyMedium)
                    IconButton(
                        onClick = { if (copies > 1) copies-- },
                        enabled = !working && copies > 1
                    ) { Text("-", style = MaterialTheme.typography.titleLarge) }
                    Text("$copies", style = MaterialTheme.typography.titleMedium)
                    IconButton(
                        onClick = { if (copies < 100) copies++ },
                        enabled = !working && copies < 100
                    ) { Text("+", style = MaterialTheme.typography.titleLarge) }
                }
                Spacer(Modifier.height(4.dp))
                PrintSpacingControls(
                    beforeDots = feedBefore,
                    afterDots = feedAfter,
                    enabled = !working,
                    onBeforeDots = { feedBefore = it },
                    onAfterDots = { feedAfter = it },
                )
                Spacer(Modifier.height(8.dp))

                (printerState as? PrinterState.Ready)?.let { ready ->
                    Text(
                        if (ready.transport == PrinterTransport.CLASSIC) "经典蓝牙 · 高速" else "低功耗蓝牙",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                }

                if (printerState !is PrinterState.Printing) {
                    PrinterConnectSection(
                        state = printerState,
                        hasSavedPrinter = savedPrinter != null,
                        onConnect = { vm.connect() },
                        onOpenSettings = onOpenPrinterSettings,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                val printing = printerState as? PrinterState.Printing
                if (printing != null) {
                    Text(
                        stringResource(R.string.print_label_progress, printing.copy, printing.copies),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { printing.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (working) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { vm.print(image, media, copies, feedBefore, feedAfter) },
                    enabled = !working && printerState is PrinterState.Ready,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_print)) }
                OutlinedButton(onClick = onDismiss, enabled = !working, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
}
