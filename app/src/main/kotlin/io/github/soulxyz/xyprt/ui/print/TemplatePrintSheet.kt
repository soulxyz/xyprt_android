package io.github.soulxyz.xyprt.ui.print

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import io.github.soulxyz.xyprt.model.LabelTemplate
import io.github.soulxyz.xyprt.model.Placeholders
import io.github.soulxyz.xyprt.printer.MediaType
import io.github.soulxyz.xyprt.render.LabelRenderer
import io.github.soulxyz.xyprt.ui.components.ClearButton
import io.github.soulxyz.xyprt.ui.components.MonoPaperPreview
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Print dialog for templates: resolves placeholders (with input fields for
 * {frage:...}), shows the pixel-accurate 1-bit preview of the first copy and
 * updates counter and history after printing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatePrintSheet(
    template: LabelTemplate,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit = {},
    vm: TemplatePrintViewModel = viewModel(),
) {
    val working by vm.working.collectAsState()
    val error by vm.error.collectAsState()
    val done by vm.done.collectAsState()
    val printerState by vm.printerState.collectAsState()
    val savedPrinter by vm.savedPrinter.collectAsState(initial = null)

    val questions = remember(template.id) { Placeholders.questions(template.elements) }
    var answers by remember(template.id) { mutableStateOf(questions.associateWith { "" }) }
    var copies by remember { mutableIntStateOf(1) }
    val media = MediaType.CONTINUOUS // BY-288 uses continuous paper in the normal UI.

    val previewImage = remember(template, answers) {
        val now = Date()
        val context = Placeholders.Context(
            dateText = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(now),
            timeText = SimpleDateFormat("HH:mm", Locale.GERMANY).format(now),
            counter = template.counterValue,
            answers = answers,
        )
        val resolved = Placeholders.resolve(template.elements, context)
        val rendered = LabelRenderer.renderMono(template.spec, LabelRenderer.reanchor(template.elements, resolved))
        if (template.spec.autoLength) rendered.trimTrailingWhite() else rendered
    }

    val view = LocalView.current
    DisposableEffect(working) {
        view.keepScreenOn = working
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(done) {
        if (done) {
            vm.reset()
            onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = { if (!working) onDismiss() }) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(stringResource(R.string.print_title, template.name), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            questions.forEach { question ->
                OutlinedTextField(
                    value = answers[question].orEmpty(),
                    onValueChange = { answers = answers + (question to it) },
                    label = { Text(question) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (!working && answers[question].orEmpty().isNotEmpty()) {
                            ClearButton { answers = answers + (question to "") }
                        }
                    },
                    enabled = !working
                )
                Spacer(Modifier.height(8.dp))
            }

            Text(
                stringResource(R.string.print_preview, (previewImage.height + 7) / 8),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(4.dp))
            MonoPaperPreview(
                image = previewImage,
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
                if (Placeholders.containsCounter(template.elements)) {
                    Text(
                        stringResource(R.string.print_counter_start, template.counterValue),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            if (printerState !is PrinterState.Printing) {
                PrinterConnectSection(
                    state = printerState,
                    hasSavedPrinter = savedPrinter != null,
                    onConnect = { vm.connect() },
                    onOpenSettings = { onDismiss(); onOpenSettings() },
                )
                Spacer(Modifier.height(8.dp))
            }

            val printing = printerState as? PrinterState.Printing
            if (printing != null) {
                Text(stringResource(R.string.print_label_progress, printing.copy, printing.copies), style = MaterialTheme.typography.bodySmall)
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { vm.print(template, media, copies, answers) },
                    enabled = !working && printerState is PrinterState.Ready
                ) { Text(stringResource(R.string.action_print)) }
                OutlinedButton(onClick = onDismiss, enabled = !working) { Text(stringResource(R.string.action_cancel)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
