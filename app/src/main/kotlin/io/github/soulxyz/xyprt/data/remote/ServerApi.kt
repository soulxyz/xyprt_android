package io.github.soulxyz.xyprt.data.remote

import io.github.soulxyz.xyprt.BuildConfig
import io.github.soulxyz.xyprt.security.AppIntegritySignal
import io.github.soulxyz.xyprt.security.ReleaseContract
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Central network boundary. Protected calls use one canonical DeviceAuth protocol here. */
class ServerApi(
    private val json: Json,
    private val identity: DeviceIdentity,
    private val integrity: AppIntegritySignal,
) {
    val baseUrl: String = BuildConfig.UPDATE_API_BASE_URL.trimEnd('/')
    private val base = URL(baseUrl)

    suspend fun getJson(path: String): JsonObject = withContext(Dispatchers.IO) {
        readJson(open(path, "GET"))
    }

    suspend fun postJson(path: String, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val bytes = encode(body)
        val c = open(path, "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.outputStream.use { it.write(bytes) }
        readJson(c)
    }

    suspend fun signedGet(path: String): JsonObject = withContext(Dispatchers.IO) {
        val c = open(path, "GET").apply { instanceFollowRedirects = false }
        applyDeviceAuthHeaders(c, "GET", URL(absolute(path)), ByteArray(0))
        readJson(c)
    }

    suspend fun signedPost(path: String, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val bytes = encode(body)
        val c = open(path, "POST").apply { instanceFollowRedirects = false }
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        applyDeviceAuthHeaders(c, "POST", URL(absolute(path)), bytes)
        c.outputStream.use { it.write(bytes) }
        readJson(c)
    }

    /**
     * Adds DeviceAuth only for this project's API origin. Redirects to CDN must not receive device
     * signatures. Used by the updater because it streams with Range rather than JSON helpers.
     */
    fun applyDeviceAuthHeaders(
        connection: HttpURLConnection,
        method: String,
        url: URL,
        body: ByteArray = ByteArray(0),
    ) {
        if (!isFirstParty(url)) return
        val timestamp = System.currentTimeMillis() / 1000L
        val nonce = identity.newNonce()
        val target = requestTarget(url)
        val version = identity.keyVersion
        val signature = identity.signRequest(method, target, timestamp, nonce, body, version)
        connection.setRequestProperty("X-Device-Id", identity.installationId)
        connection.setRequestProperty("X-Device-Time", timestamp.toString())
        connection.setRequestProperty("X-Device-Nonce", nonce)
        connection.setRequestProperty("X-Device-Key-Version", version.toString())
        connection.setRequestProperty("X-Device-Signature", signature)
        connection.setRequestProperty("X-App-Channel", ReleaseContract.channel)
        connection.setRequestProperty("X-Build-Contract", ReleaseContract.contractId)
        connection.setRequestProperty("X-App-Package", integrity.packageName)
        integrity.signingCertificateSha256?.let { connection.setRequestProperty("X-App-Signing-Sha256", it) }
    }

    fun isFirstParty(url: URL): Boolean =
        url.protocol.equals(base.protocol, true) &&
            url.host.equals(base.host, true) &&
            effectivePort(url) == effectivePort(base)

    suspend fun downloadAbsolute(url: String, maxBytes: Long = 192L * 1024 * 1024): ByteArray = withContext(Dispatchers.IO) {
        val parsed = URL(url)
        val c = (parsed.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "xyprt-android/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = c.responseCode
            if (code !in 200..299) error("下载服务返回 HTTP $code")
            val declared = c.contentLengthLong
            if (declared > maxBytes) error("文件过大")
            val out = ByteArrayOutputStream(if (declared in 1..Int.MAX_VALUE) declared.toInt() else 64 * 1024)
            c.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) error("文件超过允许大小")
                    out.write(buf, 0, n)
                }
            }
            out.toByteArray()
        } finally { c.disconnect() }
    }

    /**
     * Streams remote objects with resume support. When [authenticateFirstParty] is true only the
     * first-party API hop receives DeviceAuth headers; a redirect to CDN is followed manually and
     * never receives the device signature.
     */
    suspend fun downloadAbsoluteToFile(
        url: String,
        target: File,
        maxBytes: Long = 512L * 1024 * 1024,
        expectedSize: Long? = null,
        authenticateFirstParty: Boolean = false,
    ): Long = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        var existing = target.takeIf { it.isFile }?.length()?.coerceAtLeast(0L) ?: 0L
        var current = URL(url)
        var redirects = 0
        while (true) {
            val c = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 45_000
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "xyprt-android/${BuildConfig.VERSION_NAME}")
                if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
            }
            if (authenticateFirstParty && isFirstParty(current)) applyDeviceAuthHeaders(c, "GET", current)
            val code = c.responseCode
            if (code in 300..399) {
                if (redirects++ >= 6) { c.disconnect(); error("下载重定向过多") }
                val location = c.getHeaderField("Location") ?: run { c.disconnect(); error("下载重定向缺少地址") }
                val next = URL(current, location)
                c.disconnect()
                current = next
                continue
            }
            try {
                if (code !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) error("下载服务返回 HTTP $code")
                if (existing > 0L && code == HttpURLConnection.HTTP_OK) {
                    existing = 0L
                    target.delete()
                } else if (existing > 0L && code == HttpURLConnection.HTTP_PARTIAL) {
                    val contentRange = c.getHeaderField("Content-Range").orEmpty()
                    val rangeStart = Regex("^bytes\\s+(\\d+)-").find(contentRange)?.groupValues?.getOrNull(1)?.toLongOrNull()
                    if (rangeStart != existing) error("断点续传响应范围无效")
                }
                val declared = c.contentLengthLong.takeIf { it >= 0L }
                val total = when {
                    expectedSize != null && expectedSize > 0 -> expectedSize
                    declared != null -> existing + declared
                    else -> null
                }
                if (total != null && total > maxBytes) error("文件过大")
                val raf = RandomAccessFile(target, "rw")
                if (existing == 0L) raf.setLength(0L) else raf.seek(existing)
                var downloaded = existing
                val buffer = ByteArray(128 * 1024)
                c.inputStream.use { input ->
                    raf.use { out ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            downloaded += n
                            if (downloaded > maxBytes) error("文件超过允许大小")
                            out.write(buffer, 0, n)
                        }
                        out.fd.sync()
                    }
                }
                if (expectedSize != null && expectedSize > 0L && downloaded != expectedSize) {
                    error("下载大小不完整（$downloaded / $expectedSize）")
                }
                return@withContext downloaded
            } finally { c.disconnect() }
        }
        @Suppress("UNREACHABLE_CODE")
        0L
    }

    fun absolute(path: String): String = if (path.startsWith("http://") || path.startsWith("https://")) path else baseUrl + (if (path.startsWith('/')) path else "/$path")

    private fun open(path: String, method: String): HttpURLConnection = (URL(absolute(path)).openConnection() as HttpURLConnection).apply {
        connectTimeout = 6_000
        readTimeout = 12_000
        instanceFollowRedirects = true
        requestMethod = method
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "xyprt-android/${BuildConfig.VERSION_NAME}")
        setRequestProperty("X-App-Channel", ReleaseContract.channel)
        setRequestProperty("X-Build-Contract", ReleaseContract.contractId)
        setRequestProperty("X-App-Package", integrity.packageName)
        integrity.signingCertificateSha256?.let { setRequestProperty("X-App-Signing-Sha256", it) }
        useCaches = false
    }

    private fun readJson(c: HttpURLConnection): JsonObject {
        try {
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { buildJsonObject { put("ok", false); put("error", "invalid_server_response") } }
            if (code !in 200..299) error(root.string("error") ?: "服务返回 HTTP $code")
            if (root.boolean("ok") == false) error(root.string("error") ?: "服务暂时不可用")
            return root
        } finally { c.disconnect() }
    }

    private fun encode(body: JsonObject): ByteArray = json.encodeToString(JsonElement.serializer(), body).toByteArray(Charsets.UTF_8)

    private fun requestTarget(url: URL): String = buildString {
        append(url.path.ifBlank { "/" })
        if (!url.query.isNullOrEmpty()) append('?').append(url.query)
    }

    private fun effectivePort(url: URL): Int = if (url.port >= 0) url.port else url.defaultPort
}

internal fun JsonObject.string(key: String): String? = this[key]?.let { e -> runCatching { e.jsonPrimitive.content }.getOrNull() }
internal fun JsonObject.int(key: String): Int? = string(key)?.toIntOrNull()
internal fun JsonObject.long(key: String): Long? = string(key)?.toLongOrNull()
internal fun JsonObject.boolean(key: String): Boolean? = string(key)?.toBooleanStrictOrNull()
