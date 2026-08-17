package io.github.soulxyz.xyprt.ui.cocreator

import android.Manifest
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.soulxyz.xyprt.App
import io.github.soulxyz.xyprt.ui.components.SimpleMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val SPONSOR_QR_URL = "https://api.xyprt.5am.top/sponsor.jpg"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoCreatorScreen(onBack: () -> Unit, onOpenCapabilities: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as App).container }
    val repo = container.coCreator
    val state by repo.state.collectAsState()
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var activating by remember { mutableStateOf(false) }
    var celebrate by remember { mutableStateOf(false) }
    var showSupport by remember { mutableStateOf(false) }
    var qrBytes by remember { mutableStateOf<ByteArray?>(null) }
    var qrLoading by remember { mutableStateOf(false) }
    var qrReload by remember { mutableIntStateOf(0) }
    var confirmQr by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<ByteArray?>(null) }

    fun saveAndOpen(bytes: ByteArray) {
        scope.launch {
            val saved = withContext(Dispatchers.IO) { saveQr(context, bytes) }
            if (saved == null) {
                Toast.makeText(context, "没能保存二维码，请稍后再试", Toast.LENGTH_LONG).show()
                return@launch
            }
            when (openWechat(context)) {
                WechatOpenResult.APP -> Toast.makeText(context, "二维码已保存，正在打开微信", Toast.LENGTH_LONG).show()
                WechatOpenResult.FAILED -> Toast.makeText(context, "二维码已保存，请手动打开微信", Toast.LENGTH_LONG).show()
            }
        }
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingSave?.let { bytes ->
            pendingSave = null
            if (granted) saveAndOpen(bytes) else Toast.makeText(context, "没有相册写入权限，暂时无法保存二维码", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(showSupport, qrReload) {
        if (showSupport && qrBytes == null && !qrLoading) {
            qrLoading = true
            qrBytes = runCatching { container.serverApi.downloadAbsolute(SPONSOR_QR_URL, 5L * 1024 * 1024) }.getOrNull()
            qrLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("共创计划") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Card(
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = if (state.active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = .85f), modifier = Modifier.size(64.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text(if (state.active) "🎉" else "✦", style = MaterialTheme.typography.headlineMedium) }
                        }
                        Text(
                            if (state.active) "已加入共创计划" else "共创计划",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            if (state.active) "这台设备已获得当前共创资格。可用的测试功能会显示在这里。"
                            else "部分正在测试的功能，会先在这里小范围开放。",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .78f)) {
                            Text(
                                state.planBadge.ifBlank { "小范围开放" },
                                Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (state.active) Button(onClick = onOpenCapabilities, modifier = Modifier.fillMaxWidth()) { Text("查看可用功能") }
                    }
                }

                if (!state.active) {
                    Card(shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("已有共创码？", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("输入共创码，为这台设备开通共创资格。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(
                                value = code,
                                onValueChange = { code = it.uppercase() },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("共创码") },
                                placeholder = { Text("XXXX-XXXX-XXXX-XXXX") },
                                shape = RoundedCornerShape(16.dp),
                            )
                            Button(
                                enabled = code.isNotBlank() && !activating,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    scope.launch {
                                        activating = true
                                        val result = repo.activate(code)
                                        activating = false
                                        if (result.isSuccess) {
                                            celebrate = true
                                            code = ""
                                        } else Toast.makeText(context, friendlyActivationError(result.exceptionOrNull()?.message), Toast.LENGTH_LONG).show()
                                    }
                                },
                            ) {
                                if (activating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("加入共创")
                            }
                        }
                    }
                }

                Card(shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("当前开放", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (state.planMarkdown.isBlank()) Text("暂时没有新的共创公告。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) else SimpleMarkdown(state.planMarkdown)
                    }
                }

                Card(shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("为什么是小范围开放？", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "增强识别和部分试验功能会占用额外的服务器、测试和维护资源，因此共创码会优先提供给参与支持或受邀测试的用户。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { showSupport = !showSupport }) {
                            Text(if (showSupport) "收起" else "查看支持方式")
                        }
                    }
                }

                if (showSupport) {
                    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("支持口袋小印", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (state.active) "共创资格已经生效。如果口袋小印对你有帮助，也欢迎继续支持项目维护。"
                                else "支持完全自愿。相关资源会继续用于服务器、设备适配和测试。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            when {
                                qrLoading -> CircularProgressIndicator()
                                qrBytes != null -> {
                                    val bmp = remember(qrBytes) { BitmapFactory.decodeByteArray(qrBytes, 0, qrBytes!!.size) }
                                    if (bmp != null) Image(bmp.asImageBitmap(), "支持二维码", Modifier.size(220.dp).clickable { confirmQr = true })
                                }
                                else -> {
                                    Text("二维码暂时没加载出来")
                                    OutlinedButton(onClick = { qrBytes = null; qrLoading = false; qrReload++ }) { Text("再试一次") }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            if (celebrate) ConfettiCelebration(onFinished = { celebrate = false })
        }
    }

    if (confirmQr) {
        AlertDialog(
            onDismissRequest = { confirmQr = false },
            title = { Text("保存二维码？") },
            text = { Text("保存到相册后，会尝试打开微信。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmQr = false
                    val bytes = qrBytes ?: return@TextButton
                    if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        pendingSave = bytes
                        storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else saveAndOpen(bytes)
                }) { Text("继续") }
            },
            dismissButton = { TextButton(onClick = { confirmQr = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ConfettiCelebration(onFinished: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    val sparks = remember {
        Random(731).let { r ->
            List(48) {
                CelebrationSpark(
                    angle = r.nextFloat() * (PI * 2).toFloat(),
                    reach = .65f + r.nextFloat() * .45f,
                    delay = r.nextFloat() * .12f,
                    dot = r.nextBoolean(),
                )
            }
        }
    }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(920)); onFinished() }
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(size.width * .5f, size.height * .26f)
        sparks.forEachIndexed { index, spark ->
            val t = ((progress.value - spark.delay) / (1f - spark.delay)).coerceIn(0f, 1f)
            if (t <= 0f) return@forEachIndexed
            val eased = 1f - (1f - t) * (1f - t)
            val radius = size.minDimension * .30f * spark.reach * eased
            val ux = cos(spark.angle)
            val uy = sin(spark.angle)
            val alpha = ((1f - t) * 1.25f).coerceIn(0f, 1f)
            val color = if (index % 4 == 0) secondary else primary
            val point = Offset(center.x + ux * radius, center.y + uy * radius)
            if (spark.dot) {
                drawCircle(color.copy(alpha = alpha), radius = 3.dp.toPx(), center = point)
            } else {
                val inner = radius * .82f
                drawLine(
                    color.copy(alpha = alpha),
                    Offset(center.x + ux * inner, center.y + uy * inner),
                    point,
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }
    }
}

private data class CelebrationSpark(
    val angle: Float,
    val reach: Float,
    val delay: Float,
    val dot: Boolean,
)


private fun friendlyActivationError(message: String?): String = when {
    message.isNullOrBlank() -> "激活没成功，请稍后再试"
    message.contains("invalid_sponsor_code", true) -> "这张卡密好像不对，请检查一下有没有输错"
    message.contains("sponsor_code_disabled", true) -> "这张卡密目前已经停用"
    message.contains("sponsor_code_expired", true) -> "这张卡密已经过期"
    message.contains("device_limit", true) -> "这张卡密已经达到可激活设备数量"
    message.contains("device_identity_mismatch", true) -> "设备身份没有同步成功。App 已经尝试自动修复，请再点一次；如果仍失败再联系维护者"
    else -> "激活没成功：$message"
}

private fun saveQr(context: Context, bytes: ByteArray): Uri? = runCatching {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("invalid image")
    try {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "xyprt-support-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/口袋小印")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
        try {
            context.contentResolver.openOutputStream(uri, "w")?.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) error("encode failed")
                stream.flush()
            } ?: error("open failed")
            if (Build.VERSION.SDK_INT >= 29) {
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
            // Do not treat a successful insert as success until the media provider can read it back.
            val readable = context.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } == true
            if (!readable) error("verify failed")
            uri
        } catch (t: Throwable) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            throw t
        }
    } finally { bitmap.recycle() }
}.getOrNull()

private enum class WechatOpenResult { APP, FAILED }

/** Best-effort app launch. We intentionally do not depend on WeChat's private scanner activities. */
private fun openWechat(context: Context): WechatOpenResult {
    fun start(intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        true
    }.getOrDefault(false)

    val pm = context.packageManager

    // 1) Android 13+ has an IntentSender specifically for opening another app's
    //    front-door activity. Unlike package queries, this is not restricted by
    //    package visibility, so prefer it on modern devices.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val launched = runCatching {
            pm.getLaunchIntentSenderForPackage("com.tencent.mm")
                .sendIntent(context, 0, null, null, null)
            true
        }.getOrDefault(false)
        if (launched) return WechatOpenResult.APP
    }

    // 2) Android's classic launcher lookup for the official package.
    pm.getLaunchIntentForPackage("com.tencent.mm")?.let { if (start(it)) return WechatOpenResult.APP }

    // 3) Resolve the package's exported launcher Activity instead of assuming LauncherUI exists.
    val launcherQuery = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val candidates = runCatching { pm.queryIntentActivities(launcherQuery, PackageManager.MATCH_DEFAULT_ONLY) }.getOrDefault(emptyList())
    val wechat = candidates.firstOrNull { info ->
        val pkg = info.activityInfo?.packageName.orEmpty()
        val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault("")
        pkg == "com.tencent.mm" || label.equals("微信", true) || label.equals("WeChat", true)
    }
    if (wechat != null) {
        val ai = wechat.activityInfo
        if (ai != null && start(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(ai.packageName, ai.name)))) {
            return WechatOpenResult.APP
        }
    }

    // 4) Scheme as a compatibility fallback. We only ask it to open WeChat, not a private page.
    if (start(Intent(Intent.ACTION_VIEW, Uri.parse("weixin://")).setPackage("com.tencent.mm"))) return WechatOpenResult.APP

    // 5) Historical launcher class, kept last because internal class names can change between versions.
    if (start(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI")))) {
        return WechatOpenResult.APP
    }
    return WechatOpenResult.FAILED
}
