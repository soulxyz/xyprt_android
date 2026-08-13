package io.github.soulxyz.xyprt.data

import android.content.Context
import io.github.soulxyz.xyprt.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Current(val latest: UpdateInfo?) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Error(val message: String) : UpdateState
}

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val title: String,
    val notes: String,
    val releaseUrl: String,
    val sourceApkUrl: String?,
    val mirrorApkUrl: String?,
    val digestSha256: String?,
    val checkedVia: String,
)

/** Lightweight update check; install/download remains in the browser, so no storage/install permission is needed. */
class UpdateRepository(
    private val context: Context,
    private val settings: SettingsRepository,
    private val json: Json,
    private val scope: CoroutineScope,
) {
    companion object {
        const val REPOSITORY_URL = "https://github.com/soulxyz/xyprt_android"
        private const val API_LATEST = "https://api.github.com/repos/soulxyz/xyprt_android/releases/latest"
        private const val RAW_STATUS = "https://raw.githubusercontent.com/soulxyz/xyprt_android/main/update.json"
        private const val MIRROR = "https://ghfast.top/"
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    @Volatile private var checkedThisProcess = false

    fun check(force: Boolean = false) {
        if (!force && checkedThisProcess) return
        if (_state.value is UpdateState.Checking) return
        checkedThisProcess = true
        scope.launch {
            _state.value = UpdateState.Checking
            val info = runCatching { fetchBest() }
            _state.value = info.fold(
                onSuccess = { latest ->
                    if (latest != null && latest.versionCode > currentVersionCode()) UpdateState.Available(latest)
                    else UpdateState.Current(latest)
                },
                onFailure = { UpdateState.Error(it.message ?: "检查更新失败") },
            )
        }
    }

    fun currentVersionCode(): Int = semanticVersionCode(BuildConfig.VERSION_NAME)

    private suspend fun fetchBest(): UpdateInfo? = withContext(Dispatchers.IO) {
        // GitHub Release is the source of truth: its title/body are exactly what users see on the
        // release page. The lightweight update.json is only a fallback for API outages/rate limits.
        val releases = listOf(
            scope.async(Dispatchers.IO) { runCatching { fetchRelease(API_LATEST, "GitHub Release") }.getOrNull() },
            scope.async(Dispatchers.IO) { runCatching { fetchRelease("$MIRROR$API_LATEST", "Release 镜像") }.getOrNull() },
        ).awaitAll().filterNotNull()
        releases.maxByOrNull { it.versionCode }?.let { return@withContext it }

        val fallbacks = listOf(
            scope.async(Dispatchers.IO) { runCatching { fetchStatus(RAW_STATUS, "GitHub 备用源") }.getOrNull() },
            scope.async(Dispatchers.IO) { runCatching { fetchStatus("$MIRROR$RAW_STATUS", "备用镜像") }.getOrNull() },
        ).awaitAll().filterNotNull()
        fallbacks.maxByOrNull { it.versionCode }
    }

    private fun fetchRelease(url: String, via: String): UpdateInfo? {
        val root = json.parseToJsonElement(httpGet(url)).jsonObject
        val tag = root.string("tag_name")?.removePrefix("v") ?: return null
        val assets = root["assets"] as? JsonArray ?: JsonArray(emptyList())
        val apk = assets.mapNotNull { it as? JsonObject }.firstOrNull { asset ->
            asset.string("name")?.endsWith(".apk", ignoreCase = true) == true
        }
        val sourceUrl = apk?.string("browser_download_url")
        val digest = apk?.string("digest")?.removePrefix("sha256:")
        return UpdateInfo(
            versionName = tag,
            versionCode = semanticVersionCode(tag),
            title = root.string("name")?.ifBlank { null } ?: "错题小印 $tag",
            notes = root.string("body").orEmpty().trim().take(3000),
            releaseUrl = root.string("html_url") ?: "$REPOSITORY_URL/releases/latest",
            sourceApkUrl = sourceUrl,
            mirrorApkUrl = sourceUrl?.let { "$MIRROR$it" },
            digestSha256 = digest,
            checkedVia = via,
        )
    }

    /** Optional repo-side metadata fallback. It can be mirrored even when GitHub's API is rate-limited. */
    private fun fetchStatus(url: String, via: String): UpdateInfo? {
        val root = json.parseToJsonElement(httpGet(url)).jsonObject
        val version = root.string("version") ?: return null
        val apk = root.string("apk")
        val mirror = root.string("mirrorApk") ?: apk?.let { "$MIRROR$it" }
        return UpdateInfo(
            versionName = version,
            versionCode = root.string("versionCode")?.toIntOrNull() ?: semanticVersionCode(version),
            title = root.string("title") ?: "错题小印 $version",
            notes = root.string("notes").orEmpty().trim().take(3000),
            releaseUrl = root.string("release") ?: "$REPOSITORY_URL/releases/latest",
            sourceApkUrl = apk,
            mirrorApkUrl = mirror,
            digestSha256 = root.string("sha256"),
            checkedVia = via,
        )
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6_000
            c.readTimeout = 7_000
            c.instanceFollowRedirects = true
            c.requestMethod = "GET"
            c.setRequestProperty("Accept", "application/vnd.github+json, application/json")
            c.setRequestProperty("User-Agent", "xyprt-android/${BuildConfig.VERSION_NAME}")
            val code = c.responseCode
            if (code !in 200..299) error("HTTP $code")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun JsonObject.string(key: String): String? = runCatching {
        this[key]?.jsonPrimitive?.content
    }.getOrNull()
}

fun semanticVersionCode(raw: String): Int {
    val nums = raw.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val major = nums.getOrElse(0) { 0 }.coerceIn(0, 99)
    val minor = nums.getOrElse(1) { 0 }.coerceIn(0, 99)
    val patch = nums.getOrElse(2) { 0 }.coerceIn(0, 99)
    return major * 1_000_000 + minor * 10_000 + patch * 100
}
