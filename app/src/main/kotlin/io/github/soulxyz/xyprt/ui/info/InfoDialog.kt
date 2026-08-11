package io.github.soulxyz.xyprt.ui.info

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.soulxyz.xyprt.App
import io.github.soulxyz.xyprt.ui.components.rememberBlePermissionRunner
import io.github.soulxyz.xyprt.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** About: only the information a user needs right now. */
@Composable
fun InfoDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.substringBefore("-") ?: "?"
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val cardColor = MaterialTheme.colorScheme.surfaceContainerLow
    val websiteUrl = "https://github.com/soulxyz/xyprt/"
    val upstreamUrl = "https://github.com/toolicious/labler"

    val scope = rememberCoroutineScope()
    val backup = remember(context) { (context.applicationContext as App).container.backup }
    val requestPermissions = rememberBlePermissionRunner()
    var showBackupMenu by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<String?>(null) }

    fun toast(res: Int) = Toast.makeText(context, context.getString(res), Toast.LENGTH_SHORT).show()

    fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        if (!ok) Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
    }

    fun runImport(raw: String, replace: Boolean) {
        scope.launch {
            val ok = runCatching { backup.import(raw, replace) }.isSuccess
            toast(if (ok) R.string.backup_ok else R.string.backup_failed)
            if (ok) {
                val lang = backup.peekLanguage(raw)
                if (lang != currentAppLanguageTag(context)) setAppLanguage(context, lang)
                // A restored backup may set the remembered printer. Ensure the Bluetooth permission is
                // in place (request it if the user only imported and never scanned) and then start the
                // auto-reconnect so the printer connects right away instead of only after a restart.
                val app = context.applicationContext as App
                if (app.container.settings.savedPrinter.first() != null) {
                    requestPermissions { app.container.printerManager.startBackgroundReconnect() }
                } else {
                    app.container.printerManager.startBackgroundReconnect()
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = backup.export(currentAppLanguageTag(context))
                    (context.contentResolver.openOutputStream(uri) ?: error("no stream"))
                        .use { it.write(text.toByteArray()) }
                }.isSuccess
            }
            toast(if (ok) R.string.backup_ok else R.string.backup_failed)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                }.getOrNull()
            }
            if (text == null) {
                toast(R.string.backup_failed)
            } else if (backup.hasTemplates()) {
                pendingImport = text
            } else {
                runImport(text, replace = false)
            }
        }
    }

    pendingImport?.let { raw ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.backup_replace_title)) },
            text = { Text(stringResource(R.string.backup_replace_message)) },
            confirmButton = {
                TextButton(onClick = { pendingImport = null; runImport(raw, replace = true) }) {
                    Text(stringResource(R.string.backup_replace))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null; runImport(raw, replace = false) }) {
                    Text(stringResource(R.string.backup_add))
                }
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.92f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        icon = {
            // Logo large and colored like the app icon (teal circle + two-color logo), on top of everything.
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(colorResource(R.color.ic_launcher_background)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painterResource(R.drawable.ic_logo_color),
                    contentDescription = null,
                    modifier = Modifier.size(width = 36.dp, height = 44.dp)
                )
            }
        },
        title = {
            Text(stringResource(R.string.app_name))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.about_version, version),
                    style = MaterialTheme.typography.labelMedium,
                    color = muted
                )
                Text(
                    stringResource(R.string.about_author),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    stringResource(R.string.about_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(cardColor)
                        .clickable { openUrl(websiteUrl) }
                        .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        painterResource(R.drawable.ic_link),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "项目主页",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "github.com/soulxyz/xyprt",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                    }
                }
                Text(
                    "基于 LaBLEr · toolicious",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    modifier = Modifier.clickable { openUrl(upstreamUrl) }
                )
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    TextButton(onClick = { showBackupMenu = true }) {
                        Text(stringResource(R.string.backup))
                    }
                    DropdownMenu(
                        expanded = showBackupMenu,
                        onDismissRequest = { showBackupMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.backup_export)) },
                            onClick = {
                                showBackupMenu = false
                                val stamp = java.time.LocalDateTime.now()
                                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
                                exportLauncher.launch("xyprt-backup-$stamp.json")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.backup_import)) },
                            onClick = {
                                showBackupMenu = false
                                importLauncher.launch(arrayOf("application/json"))
                            }
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    )
}
