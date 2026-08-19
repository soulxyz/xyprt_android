package io.github.soulxyz.xyprt.ui.info

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.soulxyz.xyprt.App
import io.github.soulxyz.xyprt.BuildConfig
import io.github.soulxyz.xyprt.R
import io.github.soulxyz.xyprt.data.UpdateState
import io.github.soulxyz.xyprt.data.UpdateDownloadMode
import io.github.soulxyz.xyprt.data.UpdateDownloadState
import io.github.soulxyz.xyprt.ui.components.SimpleMarkdown
import io.github.soulxyz.xyprt.ui.components.rememberBlePermissionRunner
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** About + update status + portable backup. */
@Composable
fun InfoDialog(onDismiss: () -> Unit, onOpenCoCreator: () -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val container = app.container
    val version = remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val cardColor = MaterialTheme.colorScheme.surfaceContainerLow
    val websiteUrl = "https://github.com/soulxyz/xyprt_android"
    val upstreamUrl = "https://github.com/toolicious/labler"
    val updateState by container.updates.state.collectAsState()
    val updateDownloadState by container.updateDownloads.state.collectAsState()
    val coCreatorState by container.coCreator.state.collectAsState()
    val scope = rememberCoroutineScope()
    val backup = container.backup
    val requestPermissions = rememberBlePermissionRunner()
    var showBackupMenu by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<ByteArray?>(null) }

    LaunchedEffect(Unit) { container.updates.check() }

    fun toast(res: Int) = Toast.makeText(context, context.getString(res), Toast.LENGTH_SHORT).show()
    fun openUrl(url: String) {
        val ok = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!ok) Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
    }

    fun runImport(bytes: ByteArray, replace: Boolean) {
        scope.launch {
            val ok = runCatching { backup.importPackage(bytes, replace) }.isSuccess
            toast(if (ok) R.string.backup_ok else R.string.backup_failed)
            if (ok) {
                val lang = backup.peekLanguage(bytes)
                if (lang != null && lang != currentAppLanguageTag(context)) setAppLanguage(context, lang)
                if (container.settings.savedPrinter.first() != null) {
                    requestPermissions { container.printerManager.startBackgroundReconnect() }
                } else container.printerManager.startBackgroundReconnect()
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = backup.exportPackage(currentAppLanguageTag(context))
                    (context.contentResolver.openOutputStream(uri) ?: error("no stream")).use { it.write(bytes) }
                }.isSuccess
            }
            toast(if (ok) R.string.backup_ok else R.string.backup_failed)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) toast(R.string.backup_failed)
            else if (backup.hasContent()) pendingImport = bytes
            else runImport(bytes, replace = false)
        }
    }

    pendingImport?.let { bytes ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.backup_replace_title)) },
            text = { Text(stringResource(R.string.backup_replace_message)) },
            confirmButton = {
                TextButton(onClick = { pendingImport = null; runImport(bytes, replace = true) }) {
                    Text(stringResource(R.string.backup_replace))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null; runImport(bytes, replace = false) }) {
                    Text(stringResource(R.string.backup_add))
                }
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        icon = {
            Box(
                modifier = Modifier.size(72.dp).clip(CircleShape).background(colorResource(R.color.ic_launcher_background)),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.ic_logo_color), null, modifier = Modifier.size(width = 36.dp, height = 44.dp))
            }
        },
        title = { Text(stringResource(R.string.app_name)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(13.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("版本 $version · Soulxyz", style = MaterialTheme.typography.labelMedium, color = muted)
                    Text(
                        coCreatorState.editionLabel,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable { onDismiss(); onOpenCoCreator() }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(
                    "支持文字、图片、PDF、纸张扫描和自由排版，也可从其他应用直接分享打印。让打印更简单，也更顺手。",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )

                ShareToWeChatCard(
                    onShare = {
                        scope.launch {
                            val saved = runCatching { saveLogoToGallery(context) }.isSuccess
                            Toast.makeText(
                                context,
                                if (saved) "已保存到相册，正在打开微信" else "保存失败，请稍后再试",
                                Toast.LENGTH_SHORT,
                            ).show()
                            openWeChat(context)
                        }
                    },
                )

                UpdateCard(
                    state = updateState,
                    downloadState = updateDownloadState,
                    onCheck = { container.updates.check(force = true) },
                    onDownload = { info ->
                        scope.launch {
                            if (container.updateDownloads.effectiveMode(info) == UpdateDownloadMode.EXTERNAL) {
                                openUrl(info.mirrorApkUrl ?: info.sourceApkUrl ?: info.releaseUrl)
                            } else container.updateDownloads.start(info)
                        }
                    },
                    onCancel = { container.updateDownloads.cancel() },
                    onInstall = { container.updateDownloads.installPrepared() },
                    onBrowser = { info ->
                        scope.launch {
                            val url = container.updates.browserDownloadUrl(info)
                                ?: (info.mirrorApkUrl ?: info.sourceApkUrl ?: info.releaseUrl)
                            openUrl(url)
                        }
                    },
                    onSource = { info -> openUrl(info.releaseUrl) },
                    onSwitchChannel = { target ->
                        scope.launch {
                            Toast.makeText(context, "正在切换至" + if (target == "opensource") "社区版" else "共创版" + "…", Toast.LENGTH_SHORT).show()
                            container.updates.checkForChannel(target)
                        }
                    },
                )

                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cardColor)
                        .clickable { openUrl(websiteUrl) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_link), null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text("项目主页", style = MaterialTheme.typography.bodyLarge)
                        Text("github.com/soulxyz/xyprt_android", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    "基于 LaBLEr · toolicious",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    modifier = Modifier.clickable { openUrl(upstreamUrl) },
                )
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { showBackupMenu = true }) { Text("备份与迁移") }
                    DropdownMenu(expanded = showBackupMenu, onDismissRequest = { showBackupMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("导出到设备") },
                            onClick = {
                                showBackupMenu = false
                                val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
                                exportLauncher.launch("xyprt-backup-$stamp.xyprt")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("从备份恢复") },
                            onClick = {
                                showBackupMenu = false
                                importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/json"))
                            },
                        )
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
    )
}

@Composable
private fun UpdateCard(
    state: UpdateState,
    downloadState: UpdateDownloadState,
    onCheck: () -> Unit,
    onDownload: (io.github.soulxyz.xyprt.data.UpdateInfo) -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onBrowser: (io.github.soulxyz.xyprt.data.UpdateInfo) -> Unit,
    onSource: (io.github.soulxyz.xyprt.data.UpdateInfo) -> Unit,
    onSwitchChannel: (target: String) -> Unit,
) {
    val bg = MaterialTheme.colorScheme.surfaceContainerLow
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state) {
            UpdateState.Idle, UpdateState.Checking -> {
                Text(if (state is UpdateState.Checking) "正在检查更新…" else "检查更新", fontWeight = FontWeight.SemiBold)
                if (state is UpdateState.Idle) TextButton(onClick = onCheck) { Text("检查") }
            }
            is UpdateState.Current -> {
                Text("已是最新版本", fontWeight = FontWeight.SemiBold)
                state.latest?.let { info ->
                    Text("${info.releaseChannelLabel} · 最新版本 ${info.versionName}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    if (info.notes.isNotBlank()) ReleaseNotes(info.notes)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onCheck) { Text("重新检查") }
                    val targetChannel = if (BuildConfig.BUILD_EDITION == "cocreator") "opensource" else "cocreator"
                    val targetLabel = if (targetChannel == "opensource") "社区版" else "共创版"
                    OutlinedButton(onClick = { onSwitchChannel(targetChannel) }) {
                        Text("切换至$targetLabel")
                    }
                }
            }
            is UpdateState.Error -> {
                Text("暂时无法检查更新", fontWeight = FontWeight.SemiBold)
                Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onCheck) { Text("重试") }
            }
            is UpdateState.Available -> {
                val info = state.info
                Text("发现 ${info.releaseChannelLabel} ${info.versionName}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                if (info.notes.isNotBlank()) ReleaseNotes(info.notes)
                SameVersionInstallHint(info)
                val delta = info.delta
                if (delta != null && info.fullSizeBytes != null && info.fullSizeBytes > 0) {
                    Text(
                        "应用内约 ${formatBytes(delta.patchSize)}；浏览器下载 ${formatBytes(info.fullSizeBytes)}。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                when (val d = downloadState) {
                    is UpdateDownloadState.Downloading -> if (d.info.versionCode == info.versionCode) {
                        val total = d.totalBytes
                        if (total != null && total > 0) {
                            LinearProgressIndicator(progress = { (d.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            "${formatBytes(d.downloadedBytes)}${total?.let { " / ${formatBytes(it)}" }.orEmpty()}${if (d.bytesPerSecond > 0) " · ${formatBytes(d.bytesPerSecond)}/s" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onCancel) { Text("取消") }
                    } else UpdateButtons(info, onDownload, onBrowser, onSource)
                    is UpdateDownloadState.Verifying -> if (d.info.versionCode == info.versionCode) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("正在准备安装…", style = MaterialTheme.typography.bodySmall)
                    } else UpdateButtons(info, onDownload, onBrowser, onSource)
                    is UpdateDownloadState.NeedsInstallPermission -> if (d.info.versionCode == info.versionCode) {
                        Text("请允许口袋小印安装更新，然后回来继续。", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onInstall) { Text("继续安装") }
                            TextButton(onClick = { onBrowser(info) }) { Text("浏览器下载") }
                        }
                    } else UpdateButtons(info, onDownload, onBrowser, onSource)
                    is UpdateDownloadState.ReadyToInstall -> if (d.info.versionCode == info.versionCode) {
                        Text("更新已准备好。", style = MaterialTheme.typography.bodySmall)
                        SameVersionInstallHint(info)
                        Button(onClick = onInstall) { Text("安装更新") }
                    } else UpdateButtons(info, onDownload, onBrowser, onSource)
                    is UpdateDownloadState.Installing -> if (d.info.versionCode == info.versionCode) {
                        Text("请按系统提示完成更新。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        SameVersionInstallHint(info)
                    } else UpdateButtons(info, onDownload, onBrowser, onSource)
                    is UpdateDownloadState.Failed -> if (d.info?.versionCode == info.versionCode) {
                        Text(d.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onDownload(info) }) { Text("重试") }
                            if (d.canUseBrowser) OutlinedButton(onClick = { onBrowser(info) }) { Text("浏览器下载") }
                        }
                    } else UpdateButtons(info, onDownload, onBrowser, onSource)
                    UpdateDownloadState.Idle -> UpdateButtons(info, onDownload, onBrowser, onSource)
                }
            }
        }
    }
}

@Composable
private fun SameVersionInstallHint(info: io.github.soulxyz.xyprt.data.UpdateInfo) {
    if (info.editionSwitch && info.versionCode == BuildConfig.VERSION_CODE) {
        Text(
            "本次为版本切换，与当前版本号相同。若系统提示安装相同版本，请点击继续安装。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@Composable
private fun UpdateButtons(
    info: io.github.soulxyz.xyprt.data.UpdateInfo,
    onDownload: (io.github.soulxyz.xyprt.data.UpdateInfo) -> Unit,
    onBrowser: (io.github.soulxyz.xyprt.data.UpdateInfo) -> Unit,
    onSource: (io.github.soulxyz.xyprt.data.UpdateInfo) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onDownload(info) }) { Text("应用内") }
            OutlinedButton(onClick = { onBrowser(info) }) { Text("手动下载安装") }
            TextButton(onClick = { onSource(info) }) { Text("发布页") }
        }
        Text(
            "选择「应用内」更新，下载更快、更省流量。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun ShareToWeChatCard(onShare: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable { onShare() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(R.drawable.ic_link), null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text("把口袋小印分享给朋友", style = MaterialTheme.typography.bodyLarge)
            Text("点击保存图标到相册并打开微信", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun openWeChat(context: android.content.Context) {
    val intent = context.packageManager.getLaunchIntentForPackage("com.tencent.mm")
    if (intent == null) {
        Toast.makeText(context, "未安装微信", Toast.LENGTH_SHORT).show()
        return
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "打开微信失败", Toast.LENGTH_SHORT).show() }
}

private fun saveLogoToGallery(context: android.content.Context): Boolean {
    val drawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_logo_color) ?: return false
    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 256
    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 256
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    val filename = "pocketprint_logo_${System.currentTimeMillis()}.png"
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PocketPrint")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        runCatching {
            resolver.openOutputStream(uri)?.use { os -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, os) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }.isSuccess
    } else {
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        if (!dir.exists() && !dir.mkdirs()) return false
        val file = java.io.File(dir, filename)
        runCatching {
            file.outputStream().use { os -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, os) }
        }.isSuccess
    }
}

@Composable
private fun ReleaseNotes(markdown: String) {
    Text(
        text = simpleMarkdown(markdown),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** Minimal release-note Markdown: keep line breaks/bullets and support **bold** without a WebView. */
private fun simpleMarkdown(markdown: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    val bold = Regex("\\*\\*(.+?)\\*\\*")
    bold.findAll(markdown).forEach { match ->
        append(markdown.substring(cursor, match.range.first))
        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
        append(match.groupValues[1])
        pop()
        cursor = match.range.last + 1
    }
    if (cursor < markdown.length) append(markdown.substring(cursor))
}
