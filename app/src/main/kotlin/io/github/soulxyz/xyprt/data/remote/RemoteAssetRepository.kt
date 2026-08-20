package io.github.soulxyz.xyprt.data.remote

import android.content.Context
import io.github.soulxyz.xyprt.BuildConfig
import io.github.soulxyz.xyprt.data.DeltaPatchApplier
import io.github.soulxyz.xyprt.document.PrintDocument
import io.github.soulxyz.xyprt.render.FontRegistry
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

@Serializable
data class RemoteAssetDependency(
    val id: Int,
    val slug: String,
    val name: String,
    val type: String,
    val version: Int,
    val revision: Int,
    val required: Boolean,
)

@Serializable
data class RemoteAssetFile(
    val id: Int,
    val name: String,
    val size: Long,
    val blobSize: Long,
    val sha256: String,
    val blobSha256: String,
    val mime: String? = null,
    val encryptionMode: String = "none",
    val keyEnvelopeEndpoint: String? = null,
    val downloadEndpoint: String,
    val cdnUrl: String? = null,
)

@Serializable
data class RemoteAsset(
    val id: Int,
    val slug: String,
    val name: String,
    val description: String = "",
    val type: String,
    val version: Int,
    val revision: Int,
    val downloadable: Boolean,
    val locked: Boolean,
    val reason: String? = null,
    val file: RemoteAssetFile? = null,
    val preview: RemoteAssetFile? = null,
    val dependencies: List<RemoteAssetDependency> = emptyList(),
    val metadata: JsonObject? = null,
)

@Serializable
data class RemoteCollection(
    val id: Int,
    val slug: String,
    val name: String,
    val description: String = "",
    val coverAssetId: Int? = null,
    val coverAssetSlug: String? = null,
)

data class RemoteCollectionPage(
    val collection: RemoteCollection,
    val items: List<RemoteAsset>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

@Serializable
private data class AssetCatalogSnapshot(
    val revision: Int = 0,
    val context: String? = null,
    val items: List<RemoteAsset> = emptyList(),
)

data class RemoteAssetCatalogState(
    val revision: Int = 0,
    val items: List<RemoteAsset> = emptyList(),
    val refreshing: Boolean = false,
    val lastError: String? = null,
) {
    fun byId(id: Int) = items.firstOrNull { it.id == id }
    fun bySlug(slug: String) = items.firstOrNull { it.slug == slug }
}

/**
 * Remote catalog + content-addressed cache.
 *
 * Plain/font assets use a SHA-addressed plaintext namespace. Protected resources use a separate
 * ciphertext namespace and a device-bound key envelope; plaintext is exposed only as a verified
 * stream. None of this is on the legacy printing critical path.
 */
class RemoteAssetRepository(
    private val context: Context,
    private val json: Json,
    private val api: ServerApi,
    private val identity: DeviceIdentity,
    private val coCreator: CoCreatorRepository,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("xyprt_remote_assets", Context.MODE_PRIVATE)
    private val previewMutex = Mutex()
    // Downloaded commercial assets and device-bound envelopes must never be restored by Auto Backup.
    private val root = File(context.noBackupFilesDir, "asset-cache-v1").apply { mkdirs() }
    private val objects = File(root, "objects").apply { mkdirs() }
    private val encryptedObjects = File(root, "encrypted").apply { mkdirs() }
    private val temp = File(root, "tmp").apply { mkdirs() }
    private val catalogFile = File(root, "catalog-v1.json")
    private var snapshot = loadSnapshot()
    private val _catalog = MutableStateFlow(RemoteAssetCatalogState(snapshot.revision, snapshot.items))
    val catalog: StateFlow<RemoteAssetCatalogState> = _catalog

    init {
        restoreCachedFonts(snapshot.items)
        scope.launch { refresh(silent = true) }
    }

    suspend fun refresh(silent: Boolean = false) {
        if (!silent) _catalog.value = _catalog.value.copy(refreshing = true, lastError = null)
        runCatching {
            val ctx = snapshot.context?.let { "&context=${urlEncode(it)}" }.orEmpty()
            val path = "/v1/assets/manifest.php?appVersionCode=${BuildConfig.VERSION_CODE}&sinceRevision=${snapshot.revision}$ctx"
            val rootJson = if (coCreator.state.value.active) {
                coCreator.registerDevice()
                api.signedGet(path)
            } else {
                api.getJson(path)
            }
            applyManifest(rootJson["manifest"]?.jsonObject ?: error("素材清单为空"))
        }.onSuccess { next ->
            snapshot = next
            saveSnapshot(next)
            restoreCachedFonts(next.items)
            _catalog.value = RemoteAssetCatalogState(next.revision, next.items, refreshing = false)
        }.onFailure { e ->
            if (!silent) _catalog.value = _catalog.value.copy(refreshing = false, lastError = e.message)
        }
    }

    /** Required dependencies are prepared first; cycles are rejected both server- and client-side. */
    suspend fun ensureWithDependencies(asset: RemoteAsset): Result<CachedRemoteAsset> = runCatching {
        val visiting = linkedSetOf<Int>()
        suspend fun walk(item: RemoteAsset): CachedRemoteAsset {
            check(visiting.add(item.id)) { "素材依赖形成循环" }
            try {
                for (dep in item.dependencies.filter { it.required }) {
                    val dependency = _catalog.value.byId(dep.id) ?: error("缺少依赖素材 ${dep.slug}")
                    walk(dependency)
                }
                return ensureCached(item).getOrThrow()
            } finally {
                visiting.remove(item.id)
            }
        }
        walk(asset)
    }

    /** Generic path: protected assets stay encrypted at rest and return a stream-capable handle. */
    suspend fun ensureCached(asset: RemoteAsset, onProgress: ((Float) -> Unit)? = null): Result<CachedRemoteAsset> = runCatching {
        require(asset.downloadable && !asset.locked) { asset.reason ?: "当前素材不可下载" }
        if (asset.type == "template") {
            val schema = asset.metadata?.int("schemaVersion") ?: PrintDocument.SCHEMA_VERSION
            val minRenderer = asset.metadata?.int("minRendererVersion") ?: 1
            require(schema == PrintDocument.SCHEMA_VERSION) { "模板协议版本过新，请升级口袋小印" }
            require(minRenderer <= PrintDocument.RENDERER_VERSION) { "模板需要更高版本的排版引擎" }
        }
        val remote = asset.file ?: error("素材没有可下载文件")
        if (asset.type == "font") require(remote.encryptionMode == "none") { "在线字体必须使用明文字体资源" }
        require(remote.sha256.isSha256() && remote.blobSha256.isSha256()) { "素材缺少有效 SHA-256" }
        require(remote.size >= 0 && remote.blobSize > 0) { "素材大小无效" }

        cachedHandle(asset)?.let { return@runCatching it }

        val authenticateDevice = remote.encryptionMode != "none" || coCreator.state.value.active
        if (authenticateDevice) coCreator.registerDevice()
        // Binary delta is intentionally only used for plaintext objects. GCM ciphertext is not a
        // useful diff base; encrypted packs should update at the logical small-asset/chunk level.
        val previousSha = if (remote.encryptionMode == "none") {
            prefs.getString("asset_sha:${asset.id}", null)?.lowercase(Locale.ROOT)
                ?.takeIf { it.isSha256() && cachedObject(it) != null }
        } else null
        val resolutionBody = buildJsonObject {
            put("appVersionCode", BuildConfig.VERSION_CODE)
            put("assetId", asset.id)
            if (previousSha != null) put("localSha256", previousSha)
        }
        val resolutionRoot = if (authenticateDevice) {
            api.signedPost("/v1/assets/resolve.php", resolutionBody)
        } else {
            api.postJson("/v1/assets/resolve.php", resolutionBody)
        }
        val resolution = resolutionRoot["resolution"]?.jsonObject ?: error("资源解析响应无效")
        val mode = resolution.string("mode") ?: "full"
        val handle = when {
            mode == "up_to_date" && previousSha != null -> {
                cachedObject(previousSha)?.let { PlainCachedRemoteAsset(asset, it, remote.sha256, remote.size) }
                    ?: downloadFull(asset, remote, authenticateDevice, onProgress)
            }
            mode == "delta" && remote.encryptionMode == "none" -> runCatching {
                val file = applyDelta(asset, remote, previousSha, resolution, authenticateDevice)
                PlainCachedRemoteAsset(asset, file, remote.sha256, remote.size)
            }.getOrElse { downloadFull(asset, remote, authenticateDevice, onProgress) }
            else -> downloadFull(asset, remote, authenticateDevice, onProgress)
        }
        rememberInstalled(asset, remote)
        handle.plainFile?.let { registerFontIfNeeded(asset, it) }
        handle
    }

    /** Convenience retained for fonts/plain assets. Protected resources must use [ensureCached]. */
    suspend fun ensure(asset: RemoteAsset, onProgress: ((Float) -> Unit)? = null): Result<File> =
        ensureCached(asset, onProgress = onProgress).mapCatching { handle ->
            handle.plainFile ?: error("受保护资源不会以明文文件形式落盘；请使用 openInputStream()")
        }

    /** Downloads and caches a plaintext preview image; previews stay visible even for locked assets. */
    suspend fun previewFile(asset: RemoteAsset): Result<File> = runCatching {
        val remote = asset.preview ?: error("这个素材没有预览图")
        require(remote.encryptionMode == "none") { "预览图必须为明文资源" }
        require(remote.sha256.isSha256() && remote.blobSha256.isSha256()) { "预览图缺少有效 SHA-256" }
        cachedObject(remote.sha256)?.let { return@runCatching it }
        // 优先直接从 CDN 下载（manifest 中携带签名 CDN URL），绕过 PHP 发票链路。
        val cdnUrl = remote.cdnUrl
        if (cdnUrl != null) {
            val part = File(temp, "preview-${asset.id}-${remote.blobSha256}.part")
            api.downloadAbsoluteToFile(cdnUrl, part, maxBytes = 1024L * 1024, expectedSize = remote.size, authenticateFirstParty = false)
            require(remote.blobSha256.equals(remote.sha256, ignoreCase = true)) { "预览图CDN下载校验失败" }
            return@runCatching promoteToCache(part, remote.sha256)
        }
        // 回退到 ticket 流程（老版本 manifest 无 cdnUrl）
        previewMutex.withLock {
            cachedObject(remote.sha256)?.let { return@runCatching it }
            val part = downloadBlob(asset, remote, authenticateDevice = coCreator.state.value.active)
            require(remote.blobSha256.equals(remote.sha256, ignoreCase = true)) { "预览图的内容哈希不一致" }
            require(remote.blobSize == remote.size) { "预览图的逻辑大小不一致" }
            promoteToCache(part, remote.sha256)
        }
    }

    fun cached(asset: RemoteAsset): File? {
        val remote = asset.file ?: return null
        if (remote.encryptionMode != "none") return null
        return cachedObject(remote.sha256)
    }

    fun cachedHandle(asset: RemoteAsset): CachedRemoteAsset? {
        val remote = asset.file ?: return null
        return when (remote.encryptionMode) {
            "none" -> cachedObject(remote.sha256)?.let { PlainCachedRemoteAsset(asset, it, remote.sha256, remote.size) }
            "xya1-aes256-gcm" -> {
                val blob = cachedEncrypted(remote) ?: return null
                val wrapped = cachedEnvelope(remote) ?: return null
                if (!canUnwrap(wrapped)) {
                    prefs.edit().remove(envelopeKey(remote)).apply()
                    null
                } else EncryptedCachedRemoteAsset(asset, blob, wrapped, identity, remote.sha256, remote.size)
            }
            else -> null
        }
    }

    fun cachedBySha256(sha256: String): File? = cachedObject(sha256)

    /** Lightweight collection metadata for storefront/category screens. */
    suspend fun listCollections(): Result<List<RemoteCollection>> = runCatching {
        val rootJson = api.getJson("/v1/collections/list.php")
        (rootJson["items"]?.jsonArray ?: JsonArray(emptyList())).mapNotNull { element ->
            runCatching { parseCollection(element.jsonObject) }.getOrNull()
        }
    }

    /** Fetch only one lazy metadata page; content bytes are fetched only when ensure* is invoked. */
    suspend fun loadCollectionPage(collectionId: Int, cursor: String? = null, limit: Int = 20): Result<RemoteCollectionPage> = runCatching {
        require(collectionId > 0) { "合集 ID 无效" }
        val safeLimit = limit.coerceIn(1, 40)
        val cursorQuery = cursor?.takeIf { it.isNotBlank() }?.let { "&cursor=${urlEncode(it)}" }.orEmpty()
        val path = "/v1/collections/items.php?id=$collectionId&appVersionCode=${BuildConfig.VERSION_CODE}&limit=$safeLimit$cursorQuery"
        val rootJson = if (coCreator.state.value.active) {
            coCreator.registerDevice()
            api.signedGet(path)
        } else {
            api.getJson(path)
        }
        RemoteCollectionPage(
            collection = parseCollection(rootJson["collection"]?.jsonObject ?: error("合集信息为空")),
            items = (rootJson["items"]?.jsonArray ?: JsonArray(emptyList())).mapNotNull { parseAsset(it.jsonObject) },
            nextCursor = rootJson.string("nextCursor"),
            hasMore = rootJson.boolean("hasMore") ?: false,
        )
    }

    private suspend fun applyDelta(
        asset: RemoteAsset,
        remote: RemoteAssetFile,
        previousSha: String?,
        resolution: JsonObject,
        authenticateDevice: Boolean,
    ): File = withContext(Dispatchers.IO) {
        val fromSha = previousSha ?: error("缺少本地增量基线")
        val old = cachedObject(fromSha) ?: error("本地增量基线不存在")
        val patchInfo = resolution["patch"]?.jsonObject ?: error("缺少增量信息")
        val patchSha = patchInfo.string("sha256")?.lowercase(Locale.ROOT)?.takeIf { it.isSha256() } ?: error("增量 SHA-256 无效")
        val patchSize = patchInfo.long("size")?.takeIf { it > 0 } ?: error("增量大小无效")
        val endpoint = patchInfo.string("downloadEndpoint") ?: error("缺少增量下载入口")
        val patch = File(temp, "asset-${asset.id}-$patchSha.xydelta.part")
        if (!preparePartForResume(patch, patchSize, patchSha)) {
            api.downloadAbsoluteToFile(
                api.absolute(endpoint),
                patch,
                maxBytes = minOf(512L * 1024 * 1024, (patchSize * 2).coerceAtLeast(8L * 1024 * 1024)),
                expectedSize = patchSize,
                authenticateFirstParty = authenticateDevice,
            )
        }
        require(sha256(patch).equals(patchSha, ignoreCase = true)) { "素材增量包校验失败" }
        val output = File(temp, "asset-${asset.id}-${remote.sha256}.rebuilt")
        output.delete()
        try {
            DeltaPatchApplier.apply(old, patch, output)
            require(output.length() == remote.size) { "素材增量重建大小校验失败" }
            require(sha256(output).equals(remote.sha256, ignoreCase = true)) { "素材增量重建结果校验失败" }
            promoteToCache(output, remote.sha256)
        } finally {
            patch.delete()
            if (output.exists()) output.delete()
        }
    }

    private suspend fun downloadFull(asset: RemoteAsset, remote: RemoteAssetFile, authenticateDevice: Boolean, onProgress: ((Float) -> Unit)? = null): CachedRemoteAsset {
        return when (remote.encryptionMode) {
            "none" -> {
                val part = downloadBlob(asset, remote, authenticateDevice, onProgress)
                require(remote.blobSha256.equals(remote.sha256, ignoreCase = true)) { "未加密素材的内容哈希与对象哈希不一致" }
                require(remote.blobSize == remote.size) { "未加密素材的逻辑大小与对象大小不一致" }
                val file = promoteToCache(part, remote.sha256)
                PlainCachedRemoteAsset(asset, file, remote.sha256, remote.size)
            }
            "xya1-aes256-gcm" -> {
                require(remote.blobSize >= 32L) { "加密资源包过小" }
                // A lost/rotated key envelope must not force a second download of a valid ciphertext
                // object. Reuse the verified encrypted CAS blob and only refresh the device envelope.
                val blob = cachedEncrypted(remote) ?: promoteEncrypted(downloadBlob(asset, remote, authenticateDevice), remote.blobSha256)
                val wrapped = ensureEnvelope(asset, remote)
                EncryptedCachedRemoteAsset(asset, blob, wrapped, identity, remote.sha256, remote.size).also { handle ->
                    // Verify GCM tag + plaintext hash now, before reporting a download as installed.
                    handle.openInputStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (input.read(buffer) >= 0) Unit
                    }
                }
            }
            else -> error("不支持的资源加密模式 ${remote.encryptionMode}")
        }
    }

    private suspend fun downloadBlob(asset: RemoteAsset, remote: RemoteAssetFile, authenticateDevice: Boolean, onProgress: ((Float) -> Unit)? = null): File {
        val endpoint = remote.downloadEndpoint
        val sep = if ('?' in endpoint) '&' else '?'
        val ticketPath = "$endpoint${sep}appVersionCode=${BuildConfig.VERSION_CODE}"
        val issued = if (authenticateDevice) api.signedGet(ticketPath) else api.getJson(ticketPath)
        val grant = issued["download"]?.jsonObject ?: error("未获得素材下载票据")
        val ticket = grant.string("ticket") ?: error("素材下载票据为空")
        val redeemEndpoint = grant.string("redeemEndpoint") ?: "/v1/download/redeem.php"
        val redeemBody = buildJsonObject { put("ticket", ticket) }
        val redeem = if (authenticateDevice) api.signedPost(redeemEndpoint, redeemBody) else api.postJson(redeemEndpoint, redeemBody)
        val download = redeem["download"]?.jsonObject ?: error("素材下载兑换失败")
        val url = download.string("url") ?: error("素材下载地址为空")
        val part = File(temp, "asset-${asset.id}-${remote.blobSha256}.part")
        if (!preparePartForResume(part, remote.blobSize, remote.blobSha256)) {
            val max = maxOf(remote.blobSize + 1024L * 1024L, 8L * 1024 * 1024)
            api.downloadAbsoluteToFile(url, part, maxBytes = max, expectedSize = remote.blobSize, onProgress = onProgress?.let { cb -> { downloaded, total -> cb(if (total != null && total > 0) downloaded.toFloat() / total else 0f) } })
        }
        require(sha256(part).equals(remote.blobSha256, ignoreCase = true)) { "素材传输 SHA-256 校验失败" }
        return part
    }

    private suspend fun ensureEnvelope(asset: RemoteAsset, remote: RemoteAssetFile): String {
        cachedEnvelope(remote)?.takeIf(::canUnwrap)?.let { return it }
        val endpoint = remote.keyEnvelopeEndpoint ?: error("加密资源缺少 key envelope 入口")
        coCreator.registerDevice()
        val rootJson = api.signedPost(endpoint, buildJsonObject {
            put("appVersionCode", BuildConfig.VERSION_CODE)
            put("assetId", asset.id)
        })
        val envelope = rootJson["envelope"]?.jsonObject ?: error("未获得资源解密 envelope")
        require(envelope.int("fileId") == remote.id) { "资源 envelope 文件版本不匹配" }
        require(envelope.string("contentSha256")?.equals(remote.sha256, true) == true) { "资源 envelope 内容版本不匹配" }
        require(envelope.string("algorithm") == "XYA1-AES-256-GCM") { "资源 envelope 算法不支持" }
        val wrapped = envelope.string("wrappedKey") ?: error("资源 envelope 缺少密钥")
        require(canUnwrap(wrapped)) { "设备无法解开资源 envelope" }
        prefs.edit().putString(envelopeKey(remote), wrapped).apply()
        return wrapped
    }

    private fun cachedEnvelope(remote: RemoteAssetFile): String? = prefs.getString(envelopeKey(remote), null)
    private fun envelopeKey(remote: RemoteAssetFile) = "asset_envelope:${remote.id}:${remote.sha256.lowercase(Locale.ROOT)}"

    private fun canUnwrap(wrapped: String): Boolean = runCatching {
        identity.unwrapDeviceKey(wrapped).let { key ->
            try { key.size == 32 } finally { key.fill(0) }
        }
    }.getOrDefault(false)

    private fun promoteToCache(source: File, contentSha: String): File = promoteVerified(source, objectPath(contentSha), contentSha)
    private fun promoteEncrypted(source: File, blobSha: String): File = promoteVerified(source, encryptedPath(blobSha), blobSha)

    private fun promoteVerified(source: File, target: File, expectedSha: String): File {
        target.parentFile?.mkdirs()
        verifiedFile(target, expectedSha)?.let { source.delete(); return it }
        val staging = File(target.parentFile, target.name + ".new")
        staging.delete()
        if (!source.renameTo(staging)) {
            source.inputStream().use { input ->
                FileOutputStream(staging).use { output ->
                    input.copyTo(output, 128 * 1024)
                    output.fd.sync()
                }
            }
            source.delete()
        }
        require(sha256(staging).equals(expectedSha, ignoreCase = true)) { staging.delete(); "缓存写入校验失败" }
        if (!staging.renameTo(target)) {
            if (verifiedFile(target, expectedSha) != null) staging.delete()
            else { staging.delete(); error("无法原子提交素材缓存") }
        }
        return target
    }

    private fun cachedObject(sha: String): File? = if (sha.isSha256()) verifiedFile(objectPath(sha), sha) else null
    private fun cachedEncrypted(remote: RemoteAssetFile): File? = verifiedFile(encryptedPath(remote.blobSha256), remote.blobSha256)

    private fun verifiedFile(file: File, expectedSha: String): File? {
        if (!file.isFile) return null
        if (!sha256(file).equals(expectedSha, ignoreCase = true)) { file.delete(); return null }
        return file
    }

    private fun objectPath(sha: String): File {
        val lower = sha.lowercase(Locale.ROOT)
        return File(File(objects, lower.substring(0, 2)), lower)
    }

    private fun encryptedPath(sha: String): File {
        val lower = sha.lowercase(Locale.ROOT)
        return File(File(encryptedObjects, lower.substring(0, 2)), "$lower.xya1")
    }

    /** Returns true when a previous .part is already complete and verified. */
    private fun preparePartForResume(part: File, expectedSize: Long, expectedSha: String): Boolean {
        if (!part.isFile) return false
        if (part.length() > expectedSize) {
            part.delete()
            return false
        }
        if (part.length() == expectedSize) {
            if (sha256(part).equals(expectedSha, ignoreCase = true)) return true
            part.delete()
        }
        return false
    }

    private fun applyManifest(m: JsonObject): AssetCatalogSnapshot {
        val revision = m.int("revision") ?: snapshot.revision
        val context = m.string("context") ?: snapshot.context
        val reset = m.boolean("resetRequired") ?: false
        val map = if (reset) linkedMapOf() else snapshot.items.associateByTo(linkedMapOf()) { it.id }
        val base = if (reset) m["items"]?.jsonArray ?: JsonArray(emptyList()) else m["upserts"]?.jsonArray ?: JsonArray(emptyList())
        for (e in base) parseAsset(e.jsonObject)?.let { map[it.id] = it }
        if (!reset) {
            for (e in m["deletions"]?.jsonArray ?: JsonArray(emptyList())) {
                val o = e.jsonObject
                o.int("id")?.let(map::remove)
                o.string("slug")?.let { slug -> map.entries.removeAll { it.value.slug == slug } }
            }
        }
        return AssetCatalogSnapshot(revision, context, map.values.sortedWith(compareBy<RemoteAsset> { it.type }.thenBy { it.id }))
    }

    private fun parseCollection(o: JsonObject): RemoteCollection = RemoteCollection(
        id = o.int("id") ?: error("collection id missing"),
        slug = o.string("slug") ?: error("collection slug missing"),
        name = o.string("name") ?: error("collection name missing"),
        description = o.string("description").orEmpty(),
        coverAssetId = o.int("coverAssetId"),
        coverAssetSlug = o.string("coverAssetSlug"),
    )

    private fun parseAsset(o: JsonObject): RemoteAsset? = runCatching {
        RemoteAsset(
            id = o.int("id") ?: error("asset id missing"),
            slug = o.string("slug") ?: error("asset slug missing"),
            name = o.string("name") ?: error("asset name missing"),
            description = o.string("description").orEmpty(),
            type = o.string("type") ?: "resource",
            version = o.int("version") ?: 1,
            revision = o.int("revision") ?: 1,
            downloadable = o.boolean("downloadable") ?: false,
            locked = o.boolean("locked") ?: true,
            reason = o.string("reason"),
            file = o["file"]?.let { parseFile(it.jsonObject) },
            preview = o["preview"]?.let { runCatching { parseFile(it.jsonObject) }.getOrNull() },
            dependencies = (o["dependencies"]?.jsonArray ?: JsonArray(emptyList())).mapNotNull { e ->
                val d = e.jsonObject
                runCatching {
                    RemoteAssetDependency(
                        d.int("id")!!, d.string("slug")!!, d.string("name").orEmpty(), d.string("type").orEmpty(),
                        d.int("version") ?: 1, d.int("revision") ?: 1, d.boolean("required") ?: true,
                    )
                }.getOrNull()
            },
            metadata = o["metadata"]?.let { runCatching { it.jsonObject }.getOrNull() },
        )
    }.getOrNull()

    private fun parseFile(o: JsonObject): RemoteAssetFile {
        val encryption = o["encryption"]?.let { runCatching { it.jsonObject }.getOrNull() }
        return RemoteAssetFile(
            id = o.int("id") ?: error("file id missing"),
            name = o.string("name") ?: "asset.bin",
            size = o.long("size") ?: 0L,
            blobSize = o.long("blobSize") ?: o.long("size") ?: 0L,
            sha256 = o.string("sha256")?.lowercase(Locale.ROOT) ?: error("content sha missing"),
            blobSha256 = (o.string("blobSha256") ?: o.string("sha256"))?.lowercase(Locale.ROOT) ?: error("blob sha missing"),
            mime = o.string("mime"),
            encryptionMode = encryption?.string("mode") ?: "none",
            keyEnvelopeEndpoint = encryption?.string("keyEnvelopeEndpoint"),
            downloadEndpoint = o.string("downloadEndpoint") ?: error("download endpoint missing"),
            cdnUrl = o.string("cdnUrl"),
        )
    }

    private fun loadSnapshot(): AssetCatalogSnapshot = runCatching {
        if (!catalogFile.isFile) return@runCatching AssetCatalogSnapshot()
        json.decodeFromString<AssetCatalogSnapshot>(catalogFile.readText())
    }.getOrDefault(AssetCatalogSnapshot())

    private fun saveSnapshot(next: AssetCatalogSnapshot) {
        runCatching {
            val tmp = File(root, "catalog-v1.json.new")
            FileOutputStream(tmp).use { out ->
                out.write(json.encodeToString(next).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(catalogFile)) { tmp.copyTo(catalogFile, overwrite = true); tmp.delete() }
        }
    }

    private fun rememberInstalled(asset: RemoteAsset, remote: RemoteAssetFile) {
        prefs.edit()
            .putString("asset_sha:${asset.id}", remote.sha256.lowercase(Locale.ROOT))
            .putString("asset_blob_sha:${asset.id}", remote.blobSha256.lowercase(Locale.ROOT))
            .apply()
    }

    private fun restoreCachedFonts(items: List<RemoteAsset>) {
        items.asSequence().filter { it.type == "font" }.forEach { asset ->
            val file = cached(asset) ?: return@forEach
            registerFontIfNeeded(asset, file)
        }
    }

    private fun registerFontIfNeeded(asset: RemoteAsset, file: File) {
        if (asset.type == "font") FontRegistry.registerRemote(asset.slug, file)
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    private fun String.isSha256() = matches(Regex("[0-9a-fA-F]{64}"))
    private fun urlEncode(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8.name())

    /** 当前注册的字体数量。委托给 FontRegistry，因为它已经跟踪了所有文件 */
    fun countCachedFonts(): Int = FontRegistry.remoteFontCount()
    /** 当前注册的字体占用字节数 */
    fun cachedFontsBytes(): Long = FontRegistry.remoteFontFiles().sumOf { if (it.isFile) it.length() else 0L }

    /** 清理所有已下载的字体。委托给 FontRegistry，只删它跟踪的文件 */
    fun clearFontCache() { FontRegistry.clearRemoteFonts() }

    /** 获取更新缓存大小（字节）。目录由 UpdateDownloadManager 持有 */
    fun getUpdateCacheSizeBytes(): Long {
        val updateDir = File(context.filesDir, "updates")
        if (!updateDir.isDirectory) return 0
        return updateDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** 清理所有更新缓存（包括当前版本） */
    fun clearUpdateCache() {
        val updateDir = File(context.filesDir, "updates")
        if (updateDir.isDirectory) {
            updateDir.listFiles()?.forEach { it.delete() }
        }
    }
}
