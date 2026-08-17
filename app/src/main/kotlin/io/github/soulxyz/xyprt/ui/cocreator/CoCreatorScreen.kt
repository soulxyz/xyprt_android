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
                WechatOpenResult.APP -> Toast.makeText(context, "二维码已保存到 图片/口袋小印，已尝试打开微信", Toast.LENGTH_LONG).show()
                WechatOpenResult.FAILED -> Toast.makeText(context, "二维码已保存到 图片/口袋小印。没能自动打开微信，请手动打开微信即可", Toast.LENGTH_LONG).show()
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
                title = { Text("共创者计划") },
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
                            if (state.active) "欢迎回来，共创者" else "一起把口袋小印慢慢做好",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            if (state.active) "这台设备已经激活。新东西可以先试，有问题也欢迎直接告诉我们。"
                            else "稳定版会一直正常维护。共创计划给愿意一起试新功能、反馈问题的人一个更近的入口。",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .78f)) {
                            Text(state.editionLabel, Modifier.padding(horizontal = 13.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        if (state.active) Button(onClick = onOpenCapabilities, modifier = Modifier.fillMaxWidth()) { Text("看看增强能力") }
                    }
                }

                if (!state.active) {
                    Card(shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("已经有卡密？", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("填进去就可以激活这台设备，不需要额外注册账号。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(
                                value = code,
                                onValueChange = { code = it.uppercase() },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("卡密") },
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
                                if (activating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("激活这台设备")
                            }
                        }
                    }
                }

                Card(shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("能多得到什么？", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("• 一些还在打磨的新功能，可以更早试到\n• 设备适配、问题和建议，我们会优先看\n• 可以更直接地聊使用体验和开发想法\n• 偶尔会有额外模板、实验资源或增强能力", style = MaterialTheme.typography.bodyMedium)
                        Text("新功能会边试边改，只有真正好用的才留下；稳定版的正常更新不受影响。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Card(shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("为什么会有这个计划？", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("适配、测试、服务器和下载分发都需要持续投入；增强识别还会有模型训练与算力成本。支持完全自愿，不影响稳定版正常使用和更新。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { showSupport = !showSupport }) { Text(if (showSupport) "先收起来" else "没有卡密？想顺手支持一下") }
                    }
                }

                if (showSupport) {
                    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("请我们喝杯奶茶 ☕", fontWeight = FontWeight.SemiBold)
                            Text("完全自愿。点二维码后，我们会先保存到相册，再尝试打开微信。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
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
            title = { Text("保存二维码并尝试打开微信？") },
            text = { Text("会先把二维码保存到“图片/口袋小印”，然后尝试打开微信。如果系统或微信没有响应，直接手动打开微信就可以。") },
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
    val particles = remember { Random(731).let { r -> List(72) { ConfettiParticle(r.nextFloat(), r.nextFloat(), r.nextFloat(), r.nextFloat(), r.nextBoolean()) } } }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(1900)); onFinished() }
    Canvas(Modifier.fillMaxSize()) {
        particles.forEachIndexed { i, p ->
            val t = progress.value
            val startX = size.width * (.15f + .7f * p.x)
            val startY = size.height * .38f
            val angle = (-PI * .92 + PI * .84 * p.angle).toFloat()
            val speed = size.minDimension * (.30f + .65f * p.speed)
            val x = startX + cos(angle) * speed * t
            val y = startY + sin(angle) * speed * t + size.height * .42f * t * t
            val alpha = (1f - t * .72f).coerceIn(0f, 1f)
            val c = if (i % 3 == 0) secondary else primary
            if (p.circle) drawCircle(c.copy(alpha = alpha), radius = 3.dp.toPx() + (i % 4), center = Offset(x, y))
            else drawLine(c.copy(alpha = alpha), Offset(x, y), Offset(x + 7.dp.toPx(), y + 11.dp.toPx()), strokeWidth = 3.dp.toPx())
        }
    }
}

private data class ConfettiParticle(val x: Float, val angle: Float, val speed: Float, val spin: Float, val circle: Boolean)

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
