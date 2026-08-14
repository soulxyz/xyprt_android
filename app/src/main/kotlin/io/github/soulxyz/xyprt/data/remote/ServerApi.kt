package io.github.soulxyz.xyprt.data.remote

import io.github.soulxyz.xyprt.BuildConfig
import java.io.ByteArrayOutputStream
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

class ServerApi(private val json: Json) {
    val baseUrl: String = BuildConfig.UPDATE_API_BASE_URL.trimEnd('/')

    suspend fun getJson(path: String): JsonObject = withContext(Dispatchers.IO) {
        val c = open(path, "GET")
        readJson(c)
    }

    suspend fun postJson(path: String, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val c = open(path, "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.outputStream.use { it.write(json.encodeToString(JsonElement.serializer(), body).toByteArray(Charsets.UTF_8)) }
        readJson(c)
    }

    suspend fun downloadAbsolute(url: String, maxBytes: Long = 192L * 1024 * 1024): ByteArray = withContext(Dispatchers.IO) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
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

    fun absolute(path: String): String = if (path.startsWith("http://") || path.startsWith("https://")) path else baseUrl + (if (path.startsWith('/')) path else "/$path")

    private fun open(path: String, method: String): HttpURLConnection = (URL(absolute(path)).openConnection() as HttpURLConnection).apply {
        connectTimeout = 6_000
        readTimeout = 12_000
        instanceFollowRedirects = true
        requestMethod = method
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "xyprt-android/${BuildConfig.VERSION_NAME}")
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
}

internal fun JsonObject.string(key: String): String? = this[key]?.let { e -> runCatching { e.jsonPrimitive.content }.getOrNull() }
internal fun JsonObject.int(key: String): Int? = string(key)?.toIntOrNull()
internal fun JsonObject.long(key: String): Long? = string(key)?.toLongOrNull()
internal fun JsonObject.boolean(key: String): Boolean? = string(key)?.toBooleanStrictOrNull()
