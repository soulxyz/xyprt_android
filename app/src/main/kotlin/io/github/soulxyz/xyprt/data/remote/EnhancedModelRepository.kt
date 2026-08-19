package io.github.soulxyz.xyprt.data.remote

import android.content.Context
import io.github.soulxyz.xyprt.BuildConfig
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class EnhancedCapability(
    val id: String,
    val name: String,
    val description: String,
    val engine: String,
    val version: Int,
    val releaseLabel: String?,
    val publishedAt: Long?,
    val downloadable: Boolean,
    val locked: Boolean,
    val reason: String?,
    val fileName: String?,
    val fileSize: Long?,
    val fileSha256: String?,
    val downloadEndpoint: String?,
    val installed: Boolean = false,
)

data class EnhancedCatalogState(
    val items: List<EnhancedCapability> = emptyList(),
    val refreshing: Boolean = false,
    val lastError: String? = null,
)

class EnhancedModelRepository(
    private val context: Context,
    private val api: ServerApi,
    private val identity: DeviceIdentity,
    private val coCreator: CoCreatorRepository,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("xyprt_enhanced_models", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "enhanced-models").apply { mkdirs() }
    private val _catalog = MutableStateFlow(EnhancedCatalogState(items = loadInstalledCatalog()))
    val catalog: StateFlow<EnhancedCatalogState> = _catalog

    init { if (BuildConfig.ENHANCED_SCANNER_AVAILABLE) scope.launch { refreshCatalog(silent = true) } }

    suspend fun refreshCatalog(silent: Boolean = false) {
        if (!BuildConfig.ENHANCED_SCANNER_AVAILABLE) return
        if (!silent) _catalog.value = _catalog.value.copy(refreshing = true, lastError = null)
        runCatching {
            coCreator.registerDevice()
            val rootJson = api.signedGet("/v1/models/list.php?appVersionCode=${BuildConfig.VERSION_CODE}")
            val list = rootJson["items"]?.jsonArray ?: JsonArray(emptyList())
            list.mapNotNull { e -> runCatching { parseItem(e.jsonObject) }.getOrNull() }
        }.onSuccess { items -> _catalog.value = EnhancedCatalogState(items.map(::withInstalled)) }
            .onFailure { e -> if (!silent) _catalog.value = _catalog.value.copy(refreshing = false, lastError = e.message) }
    }

    suspend fun download(item: EnhancedCapability): Result<Unit> = runCatching {
        check(BuildConfig.ENHANCED_SCANNER_AVAILABLE) { "当前版本暂不支持增强识别" }
        require(item.downloadable && !item.locked) { item.reason ?: "当前不可下载" }
        val endpoint = item.downloadEndpoint ?: error("没有下载入口")
        coCreator.registerDevice()
        val sep = if ('?' in endpoint) '&' else '?'
        val issue = api.signedGet("$endpoint${sep}appVersionCode=${BuildConfig.VERSION_CODE}")
        val grant = issue["download"]?.jsonObject ?: error("未获得下载票据")
        val ticket = grant.string("ticket") ?: error("下载票据为空")
        val redeem = api.signedPost(grant.string("redeemEndpoint") ?: "/v1/download/redeem.php", buildJsonObject {
            put("ticket", ticket)
        })
        val redeemed = redeem["download"]?.jsonObject ?: error("下载兑换失败")
        val url = redeemed.string("url") ?: error("下载地址为空")
        val bytes = api.downloadAbsolute(url, maxBytes = 192L * 1024 * 1024)
        val expected = item.fileSha256?.lowercase()?.takeIf { it.length == 64 }
        val actual = sha256(bytes)
        if (expected != null && !actual.equals(expected, true)) error("增强能力文件校验失败")
        val target = fileFor(item.id, item.version)
        val temp = File(target.parentFile, target.name + ".part")
        temp.outputStream().use { it.write(bytes); it.fdSync() }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) { target.writeBytes(bytes); temp.delete() }
        // A model update should not slowly fill app-private storage with stale encrypted packages.
        // Delete older versions only after the new package has been fully written and verified.
        root.listFiles()?.filter { file ->
            file != target && file.name.startsWith(safeId(item.id) + "-") && file.extension == "xymodel"
        }?.forEach { it.delete() }
        rememberInstalled(item, actual)
        ensureLease(item.id)
        refreshCatalog(silent = true)
    }

    suspend fun remove(item: EnhancedCapability) {
        root.listFiles()?.filter { it.name.startsWith(safeId(item.id) + "-") }?.forEach { it.delete() }
        val ids = prefs.getStringSet("installed_ids", emptySet()).orEmpty().toMutableSet().apply { remove(item.id) }
        prefs.edit().putStringSet("installed_ids", ids).remove("version:${item.id}").remove("sha:${item.id}").remove("lease:${item.id}").remove("lease_exp:${item.id}")
            .remove("name:${item.id}").remove("description:${item.id}").remove("engine:${item.id}").remove("release:${item.id}").remove("published:${item.id}").remove("size:${item.id}").apply()
        _catalog.value = _catalog.value.copy(items = _catalog.value.items.map(::withInstalled))
    }

    suspend fun ensureLease(modelId: String): Boolean = runCatching {
        coCreator.registerDevice()
        val root = api.signedPost("/v1/models/lease.php", buildJsonObject {
            put("modelId", modelId)
            put("appVersionCode", BuildConfig.VERSION_CODE)
        })
        val lease = root["lease"]?.jsonObject ?: error("未获得使用许可")
        val wrapped = lease.string("wrappedKey") ?: error("使用许可无密钥")
        val exp = lease.long("expiresAt") ?: error("使用许可无有效期")
        prefs.edit().putString("lease:$modelId", wrapped).putLong("lease_exp:$modelId", exp).apply()
        true
    }.getOrElse { false }

    /** Returns plaintext model bytes only while a valid device-bound lease is present. Caller must zero it. */
    fun decryptInstalled(item: EnhancedCapability): ByteArray? {
        val version = prefs.getInt("version:${item.id}", -1)
        if (version != item.version) return null
        val exp = prefs.getLong("lease_exp:${item.id}", 0L)
        if (exp <= System.currentTimeMillis() / 1000L + 30L) return null
        val wrapped = prefs.getString("lease:${item.id}", null) ?: return null
        val file = fileFor(item.id, item.version)
        if (!file.isFile) return null
        val pkg = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (item.fileSha256?.length == 64 && !sha256(pkg).equals(item.fileSha256, true)) return null
        return runCatching { decryptPackage(pkg, identity.unwrapModelKey(wrapped)) }.getOrNull()
    }

    fun bestInstalled(): EnhancedCapability? = bestInstalledForEngine("quad_heatmap_mask_v1")

    fun bestInstalledForEngine(engine: String): EnhancedCapability? =
        _catalog.value.items
            .asSequence()
            .filter { it.installed && !it.locked && it.engine == engine }
            .maxWithOrNull(compareBy<EnhancedCapability> { it.version }.thenBy { it.publishedAt ?: 0L })

    private fun decryptPackage(pkg: ByteArray, key: ByteArray): ByteArray {
        try {
            require(pkg.size > 4 + 12 + 16 && pkg.copyOfRange(0, 4).contentEquals(byteArrayOf('X'.code.toByte(),'Y'.code.toByte(),'M'.code.toByte(),'1'.code.toByte()))) { "模型包格式错误" }
            require(key.size == 32) { "模型密钥长度错误" }
            val nonce = pkg.copyOfRange(4, 16)
            val ciphertextAndTag = pkg.copyOfRange(16, pkg.size)
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                doFinal(ciphertextAndTag)
            }
        } finally { key.fill(0) }
    }

    private fun rememberInstalled(item: EnhancedCapability, sha: String) {
        val ids = prefs.getStringSet("installed_ids", emptySet()).orEmpty().toMutableSet().apply { add(item.id) }
        prefs.edit().putStringSet("installed_ids", ids)
            .putInt("version:${item.id}", item.version).putString("sha:${item.id}", sha)
            .putString("name:${item.id}", item.name).putString("description:${item.id}", item.description)
            .putString("engine:${item.id}", item.engine).putString("release:${item.id}", item.releaseLabel)
            .putLong("published:${item.id}", item.publishedAt ?: 0L).putLong("size:${item.id}", item.fileSize ?: 0L).apply()
    }

    private fun loadInstalledCatalog(): List<EnhancedCapability> = prefs.getStringSet("installed_ids", emptySet()).orEmpty().mapNotNull { id ->
        val version = prefs.getInt("version:$id", -1)
        if (version <= 0 || !fileFor(id, version).isFile) return@mapNotNull null
        EnhancedCapability(
            id=id, name=prefs.getString("name:$id", null) ?: "增强文档识别", description=prefs.getString("description:$id", null).orEmpty(),
            engine=prefs.getString("engine:$id", null) ?: "quad_heatmap_mask_v1", version=version,
            releaseLabel=prefs.getString("release:$id", null), publishedAt=prefs.getLong("published:$id", 0L).takeIf { it > 0L },
            downloadable=true, locked=false, reason=null, fileName=fileFor(id, version).name,
            fileSize=prefs.getLong("size:$id", 0L).takeIf { it > 0L }, fileSha256=prefs.getString("sha:$id", null), downloadEndpoint=null, installed=true,
        )
    }

    private fun parseItem(o: JsonObject) = EnhancedCapability(
        id = o.string("id") ?: error("missing id"),
        name = o.string("name") ?: "增强识别",
        description = o.string("description").orEmpty(),
        engine = o.string("engine") ?: "unknown",
        version = o.int("version") ?: 1,
        releaseLabel = o.string("releaseLabel"),
        publishedAt = o.long("publishedAt"),
        downloadable = o.boolean("downloadable") ?: false,
        locked = o.boolean("locked") ?: true,
        reason = o.string("reason"),
        fileName = o["file"]?.let { runCatching { it.jsonObject.string("name") }.getOrNull() },
        fileSize = o["file"]?.let { runCatching { it.jsonObject.long("size") }.getOrNull() },
        fileSha256 = o["file"]?.let { runCatching { it.jsonObject.string("sha256") }.getOrNull() },
        downloadEndpoint = o["file"]?.let { runCatching { it.jsonObject.string("downloadEndpoint") }.getOrNull() },
    )

    private fun withInstalled(i: EnhancedCapability): EnhancedCapability = i.copy(installed = prefs.getInt("version:${i.id}", -1) == i.version && fileFor(i.id, i.version).isFile)
    private fun fileFor(id: String, version: Int) = File(root, "${safeId(id)}-$version.xymodel")
    private fun safeId(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun java.io.FileOutputStream.fdSync() = runCatching { fd.sync() }.getOrNull()
}
