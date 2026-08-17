package io.github.soulxyz.xyprt.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.soulxyz.xyprt.update.InstallResultReceiver
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** User preference. Server policy may still force one delivery path for a specific release. */
enum class UpdateDownloadMode { INTERNAL, EXTERNAL }

enum class ServerDownloadMode { AUTO, INTERNAL, EXTERNAL }

data class DeltaUpdateInfo(
    val url: String,
    val fromVersionCode: Int,
    val fromApkSha256: String,
    val patchSha256: String,
    val patchSize: Long,
    val resultApkSha256: String,
)

sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(
        val info: UpdateInfo,
        val downloadedBytes: Long,
        val totalBytes: Long?,
        val bytesPerSecond: Long,
        val usingDelta: Boolean,
        val note: String? = null,
    ) : UpdateDownloadState
    data class Verifying(val info: UpdateInfo, val usingDelta: Boolean) : UpdateDownloadState
    data class NeedsInstallPermission(val info: UpdateInfo, val apk: File) : UpdateDownloadState
    data class ReadyToInstall(val info: UpdateInfo, val apk: File) : UpdateDownloadState
    data class Installing(val info: UpdateInfo) : UpdateDownloadState
    data class Failed(val info: UpdateInfo?, val message: String, val canUseBrowser: Boolean = true) : UpdateDownloadState
}

/**
 * Self-hosted updater for the sideloaded build.
 *
 * Downloads stay in app-private storage, support HTTP Range resume, and are never handed to the
 * installer before package/version/signing checks succeed. Delta is opportunistic: any failure
 * automatically falls back to the full APK.
 */
class UpdateDownloadManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val state: StateFlow<UpdateDownloadState> = _state
    private var job: Job? = null
    private val dir = File(context.filesDir, "updates").apply { mkdirs() }

    suspend fun effectiveMode(info: UpdateInfo): UpdateDownloadMode = when (info.serverDownloadMode) {
        ServerDownloadMode.INTERNAL -> UpdateDownloadMode.INTERNAL
        ServerDownloadMode.EXTERNAL -> UpdateDownloadMode.EXTERNAL
        ServerDownloadMode.AUTO -> settings.updateDownloadMode.first()
    }

    fun start(info: UpdateInfo) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                if (effectiveMode(info) == UpdateDownloadMode.EXTERNAL) {
                    _state.value = UpdateDownloadState.Failed(info, "当前设置为浏览器下载", canUseBrowser = true)
                    return@launch
                }
                cleanupOtherVersions(info.versionCode)
                val deltaApk = try { tryDelta(info) } catch (c: CancellationException) { throw c } catch (_: Throwable) { null }
                val usedDelta = deltaApk != null
                val apk = deltaApk ?: downloadFull(info)
                _state.value = UpdateDownloadState.Verifying(info, usingDelta = usedDelta)
                verifyApk(apk, info)
                _state.value = UpdateDownloadState.ReadyToInstall(info, apk)
                installPrepared()
            } catch (c: CancellationException) {
                _state.value = UpdateDownloadState.Idle
                throw c
            } catch (t: Throwable) {
                _state.value = UpdateDownloadState.Failed(info, t.message ?: "更新失败")
            } finally {
                job = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = UpdateDownloadState.Idle
    }

    fun retry() {
        val info = when (val s = _state.value) {
            is UpdateDownloadState.Failed -> s.info
            is UpdateDownloadState.NeedsInstallPermission -> s.info
            is UpdateDownloadState.ReadyToInstall -> s.info
            else -> null
        } ?: return
        start(info)
    }

    fun installPrepared() {
        val current = _state.value
        val pair = when (current) {
            is UpdateDownloadState.ReadyToInstall -> current.info to current.apk
            is UpdateDownloadState.NeedsInstallPermission -> current.info to current.apk
            else -> return
        }
        val (info, apk) = pair
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateDownloadState.NeedsInstallPermission(info, apk)
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        runCatching { commitInstall(apk, info) }
            .onFailure { _state.value = UpdateDownloadState.Failed(info, "无法启动系统安装器：${it.message}") }
    }

    fun clearState() { _state.value = UpdateDownloadState.Idle }

    private suspend fun tryDelta(info: UpdateInfo): File? = withContext(Dispatchers.IO) {
        val delta = info.delta ?: return@withContext null
        if (delta.fromVersionCode != currentVersionCode()) return@withContext null
        val fullSize = info.fullSizeBytes
        if (fullSize != null && fullSize > 0 && delta.patchSize >= (fullSize * 0.70).toLong()) return@withContext null
        val source = File(context.applicationInfo.sourceDir)
        if (!source.isFile) return@withContext null

        val patch = File(dir, "${info.versionCode}.xydelta")
        val part = File(dir, "${info.versionCode}.xydelta.part")
        try {
            downloadTo(delta.url, part, delta.patchSize, info, usingDelta = true, note = "正在下载增量更新")
            if (!part.renameTo(patch)) {
                part.copyTo(patch, overwrite = true); part.delete()
            }
            require(sha256(patch).equals(delta.patchSha256, ignoreCase = true)) { "增量包校验失败" }
            _state.value = UpdateDownloadState.Verifying(info, usingDelta = true)
            val output = File(dir, "${info.versionCode}.apk")
            DeltaPatchApplier.apply(source, patch, output)
            require(sha256(output).equals(delta.resultApkSha256, ignoreCase = true)) { "增量重建结果校验失败" }
            patch.delete()
            output
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            patch.delete()
            // Keep .part so a later retry can resume, but fall back immediately for this update.
            null
        }
    }

    private suspend fun downloadFull(info: UpdateInfo): File = withContext(Dispatchers.IO) {
        val url = info.mirrorApkUrl ?: info.sourceApkUrl ?: error("这个版本没有可用的安装包下载地址")
        val target = File(dir, "${info.versionCode}.apk")
        if (target.isFile && runCatching { verifyApk(target, info) }.isSuccess) return@withContext target
        val part = File(dir, "${info.versionCode}.apk.part")
        downloadTo(url, part, info.fullSizeBytes, info, usingDelta = false, note = null)
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true); part.delete()
        }
        target
    }

    private suspend fun downloadTo(
        rawUrl: String,
        target: File,
        expectedSize: Long?,
        info: UpdateInfo,
        usingDelta: Boolean,
        note: String?,
    ) {
        target.parentFile?.mkdirs()
        var existing = target.length().coerceAtLeast(0)
        var url = URL(rawUrl)
        var redirects = 0
        var connection: HttpURLConnection
        while (true) {
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 25_000
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "xyprt-android-updater")
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }
            val code = connection.responseCode
            if (code in 300..399 && redirects++ < 6) {
                val location = connection.getHeaderField("Location") ?: error("更新下载重定向无地址")
                url = URL(url, location)
                connection.disconnect()
                continue
            }
            break
        }
        connection.useConnection {
            val code = responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                error("下载失败（HTTP $code）")
            }
            if (existing > 0 && code == HttpURLConnection.HTTP_OK) {
                target.delete(); existing = 0
            }
            val total = parseTotalBytes(this, existing) ?: expectedSize
            val raf = RandomAccessFile(target, "rw")
            if (existing == 0L) raf.setLength(0) else raf.seek(existing)
            val buffer = ByteArray(64 * 1024)
            var downloaded = existing
            var lastBytes = downloaded
            var lastAt = System.nanoTime()
            inputStream.use { input ->
                raf.use { out ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        downloaded += n
                        val now = System.nanoTime()
                        if (now - lastAt >= 250_000_000L) {
                            val seconds = (now - lastAt) / 1_000_000_000.0
                            val speed = ((downloaded - lastBytes) / seconds).toLong().coerceAtLeast(0)
                            _state.value = UpdateDownloadState.Downloading(info, downloaded, total, speed, usingDelta, note)
                            lastBytes = downloaded; lastAt = now
                        }
                    }
                }
            }
            _state.value = UpdateDownloadState.Downloading(info, downloaded, total, 0, usingDelta, note)
            if (expectedSize != null && expectedSize > 0 && downloaded != expectedSize) {
                error("下载大小不完整（$downloaded / $expectedSize）")
            }
        }
    }

    private fun parseTotalBytes(c: HttpURLConnection, existing: Long): Long? {
        val range = c.getHeaderField("Content-Range")
        if (!range.isNullOrBlank()) {
            val total = range.substringAfterLast('/').toLongOrNull()
            if (total != null && total > 0) return total
        }
        val len = c.getHeaderFieldLong("Content-Length", -1)
        return if (len > 0) existing + len else null
    }

    private fun verifyApk(apk: File, info: UpdateInfo) {
        require(apk.isFile && apk.length() > 0) { "安装包不存在" }
        info.digestSha256?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }?.let { expected ->
            require(sha256(apk).equals(expected, ignoreCase = true)) { "安装包 SHA-256 校验失败" }
        }
        val archive = archivePackageInfo(apk) ?: error("无法读取安装包信息")
        require(archive.packageName == context.packageName) { "安装包包名不匹配" }
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
        require(archiveCode == info.versionCode.toLong()) { "安装包版本号不匹配（$archiveCode != ${info.versionCode}）" }
        require(archiveCode > currentVersionCode()) { "安装包版本并不高于当前版本" }
        require(signingCompatible(archive)) { "安装包签名与当前口袋小印不兼容" }
    }

    private fun archivePackageInfo(apk: File): PackageInfo? = if (Build.VERSION.SDK_INT >= 28) {
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
    }

    private fun signingCompatible(archive: PackageInfo): Boolean {
        val installed = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val a = certificateDigests(archive)
        val b = certificateDigests(installed)
        return a.isNotEmpty() && b.isNotEmpty() && a.any { it in b }
    }

    private fun certificateDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION") info.signatures
        } ?: return emptySet()
        return signatures.map { bytesToHex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
    }

    private fun commitInstall(apk: File, info: UpdateInfo) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            FileInputStream(apk).use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output, 64 * 1024)
                    session.fsync(output)
                }
            }
            val callback = Intent(context, InstallResultReceiver::class.java).apply {
                action = InstallResultReceiver.ACTION_INSTALL_STATUS
                putExtra(InstallResultReceiver.EXTRA_VERSION_CODE, info.versionCode)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, sessionId, callback, flags)
            session.commit(pi.intentSender)
        }
        _state.value = UpdateDownloadState.Installing(info)
    }

    private fun currentVersionCode(): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }

    private fun cleanupOtherVersions(keepCode: Int) {
        dir.listFiles()?.filter { !it.name.startsWith("$keepCode.") }?.forEach { runCatching { it.delete() } }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buf); if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return bytesToHex(md.digest())
    }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}

private inline fun <T> HttpURLConnection.useConnection(block: HttpURLConnection.() -> T): T = try { block() } finally { disconnect() }

/** Binary XYDLTA1 patch applier. The patch stream itself is gzip-compressed. */
object DeltaPatchApplier {
    private val MAGIC = byteArrayOf('X'.code.toByte(), 'Y'.code.toByte(), 'D'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte(), 0)

    fun apply(oldApk: File, patch: File, output: File) {
        java.io.DataInputStream(GZIPInputStream(patch.inputStream().buffered())).use { input ->
            val magic = ByteArray(8); input.readFully(magic); require(magic.contentEquals(MAGIC)) { "不支持的增量包格式" }
            require(input.readInt() == 1) { "不支持的增量包版本" }
            val oldSize = input.readLong(); require(oldSize == oldApk.length()) { "旧安装包大小不匹配" }
            val oldSha = ByteArray(32); input.readFully(oldSha)
            require(fileSha256(oldApk).contentEquals(oldSha)) { "当前安装包与增量包基线不匹配" }
            val newSize = input.readLong(); require(newSize in 1..(512L * 1024 * 1024)) { "增量目标大小异常" }
            val newSha = ByteArray(32); input.readFully(newSha)
            val count = input.readInt(); require(count in 1..1_000_000) { "增量操作数量异常" }
            output.parentFile?.mkdirs()
            RandomAccessFile(oldApk, "r").use { old ->
                output.outputStream().buffered().use { out ->
                    val buf = ByteArray(64 * 1024)
                    repeat(count) {
                        when (input.readUnsignedByte()) {
                            0 -> {
                                val offset = input.readLong(); var left = input.readInt().toLong()
                                require(offset >= 0 && left >= 0 && offset + left <= oldSize) { "增量复制范围异常" }
                                old.seek(offset)
                                while (left > 0) {
                                    val n = old.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                    require(n > 0) { "读取旧安装包失败" }; out.write(buf, 0, n); left -= n
                                }
                            }
                            1 -> {
                                var left = input.readInt().toLong(); require(left in 0..(64L * 1024 * 1024)) { "增量数据块异常" }
                                while (left > 0) {
                                    val n = minOf(buf.size.toLong(), left).toInt(); input.readFully(buf, 0, n); out.write(buf, 0, n); left -= n
                                }
                            }
                            else -> error("未知增量操作")
                        }
                    }
                }
            }
            require(output.length() == newSize) { "增量重建大小不匹配" }
            require(fileSha256(output).contentEquals(newSha)) { "增量重建 SHA-256 不匹配" }
        }
    }

    private fun fileSha256(file: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(128 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest()
    }
}
