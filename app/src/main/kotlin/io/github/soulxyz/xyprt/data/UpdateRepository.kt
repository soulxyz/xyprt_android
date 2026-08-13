package io.github.soulxyz.xyprt.data

import android.content.Context
import io.github.soulxyz.xyprt.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

/**
 * App 只向错题小印自己的更新网关获取少量 JSON。
 * GitHub / 镜像的并发探测、缓存与下载源选择全部由 PHP 网关完成，
 * APK 下载仍交给系统浏览器，因此 App 不需要存储/安装权限。
 */
class UpdateRepository(
    private val context: Context,
    private val settings: SettingsRepository,
    private val json: Json,
    private val scope: CoroutineScope,
) {
    companion object {
        const val REPOSITORY_URL = "https://github.com/soulxyz/xyprt_android"
        private val UPDATE_API_BASE = BuildConfig.UPDATE_API_BASE_URL.trimEnd('/')
        private val API_LATEST = "$UPDATE_API_BASE/v1/update/latest.php"
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
            val info = runCatching { fetchGateway() }
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

    private suspend fun fetchGateway(): UpdateInfo? = withContext(Dispatchers.IO) {
        parseGatewayUpdate(httpGet(API_LATEST), json)
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5_000
            c.readTimeout = 7_000
            c.instanceFollowRedirects = true
            c.requestMethod = "GET"
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "xyprt-android/${BuildConfig.VERSION_NAME}")
            val code = c.responseCode
            if (code !in 200..299) error("更新服务暂时不可用（HTTP $code）")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

internal fun parseGatewayUpdate(raw: String, json: Json): UpdateInfo? {
    val root = json.parseToJsonElement(raw).jsonObject
    val ok = root.string("ok")?.toBooleanStrictOrNull() ?: false
    if (!ok) error(root.string("message") ?: "更新服务返回错误")
    val latest = root["latest"]?.jsonObject ?: return null
    val version = latest.string("version") ?: return null
    val releaseUrl = latest.string("releaseUrl") ?: UpdateRepository.REPOSITORY_URL + "/releases/latest"
    return UpdateInfo(
        versionName = version,
        versionCode = latest.string("versionCode")?.toIntOrNull() ?: semanticVersionCode(version),
        title = latest.string("title") ?: "错题小印 $version",
        notes = latest.string("notes").orEmpty().trim().take(6_000),
        releaseUrl = releaseUrl,
        sourceApkUrl = latest.string("downloadUrl"),
        mirrorApkUrl = null,
        digestSha256 = latest.string("sha256"),
        checkedVia = latest.string("checkedVia") ?: "错题小印更新服务",
    )
}

private fun JsonObject.string(key: String): String? = runCatching {
    this[key]?.jsonPrimitive?.content
}.getOrNull()

fun semanticVersionCode(raw: String): Int {
    val nums = raw.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val major = nums.getOrElse(0) { 0 }.coerceIn(0, 99)
    val minor = nums.getOrElse(1) { 0 }.coerceIn(0, 99)
    val patch = nums.getOrElse(2) { 0 }.coerceIn(0, 99)
    return major * 1_000_000 + minor * 10_000 + patch * 100
}
