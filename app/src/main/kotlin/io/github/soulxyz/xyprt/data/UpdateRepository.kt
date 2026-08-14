package io.github.soulxyz.xyprt.data

import android.content.Context
import io.github.soulxyz.xyprt.BuildConfig
import io.github.soulxyz.xyprt.data.remote.CoCreatorRepository
import io.github.soulxyz.xyprt.data.remote.DeviceIdentity
import io.github.soulxyz.xyprt.data.remote.ServerApi
import io.github.soulxyz.xyprt.data.remote.boolean
import io.github.soulxyz.xyprt.data.remote.int
import io.github.soulxyz.xyprt.data.remote.string
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
 * Update decisions are server-side. Community devices still fall back to the legacy GitHub gateway;
 * a device that activates the co-creator entitlement can immediately be offered a preview APK.
 */
class UpdateRepository(
    private val context: Context,
    private val settings: SettingsRepository,
    private val json: Json,
    private val scope: CoroutineScope,
    private val api: ServerApi,
    private val identity: DeviceIdentity,
    private val coCreator: CoCreatorRepository,
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
            val info = runCatching { fetchManaged() }.recoverCatching { fetchGateway() }
            _state.value = info.fold(
                onSuccess = { latest -> if (latest != null && latest.versionCode > currentVersionCode()) UpdateState.Available(latest) else UpdateState.Current(latest) },
                onFailure = { UpdateState.Error(it.message ?: "检查更新失败") },
            )
        }
    }

    fun currentVersionCode(): Int = BuildConfig.VERSION_CODE

    private suspend fun fetchManaged(): UpdateInfo? {
        // Refreshing device state is cheap and makes an activation effective without reinstalling the app.
        runCatching { coCreator.registerDevice() }
        val root = api.postJson("/v1/app/update-check.php", buildJsonObject {
            put("installationId", identity.installationId)
            put("version", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("edition", if (coCreator.state.value.active) "cocreator" else "community")
            put("androidIdHash", identity.androidIdHash)
            put("deviceKeyFingerprint", identity.publicKeyFingerprint)
            put("devicePublicKey", identity.publicKeyBase64)
        })
        if (root.boolean("updateAvailable") != true) return null
        val release = root["release"]?.jsonObject ?: return null
        val channel = root.string("channel") ?: "opensource"
        val version = release.string("version") ?: return null
        return if (channel == "sponsor") {
            val releaseId = release.int("id") ?: return null
            UpdateInfo(
                versionName = version,
                versionCode = release.int("versionCode") ?: semanticVersionCode(version),
                title = release.string("title") ?: "口袋小印 共创预览 $version",
                notes = release.string("notes").orEmpty().trim().take(6_000),
                releaseUrl = REPOSITORY_URL,
                sourceApkUrl = api.absolute("/v1/app/update-download.php?installationId=${identity.installationId}&releaseId=$releaseId"),
                mirrorApkUrl = null,
                digestSha256 = release["download"]?.let { runCatching { it.jsonObject.string("sha256") }.getOrNull() },
                checkedVia = "口袋小印共创更新服务",
            )
        } else parseOpenSourceRelease(release)
    }

    private suspend fun fetchGateway(): UpdateInfo? = withContext(Dispatchers.IO) { parseGatewayUpdate(httpGet(API_LATEST), json) }

    private fun parseOpenSourceRelease(latest: JsonObject): UpdateInfo? {
        val version = latest.string("version") ?: return null
        return UpdateInfo(
            versionName = version,
            versionCode = latest.int("versionCode") ?: semanticVersionCode(version),
            title = latest.string("title") ?: "口袋小印 $version",
            notes = latest.string("notes").orEmpty().trim().take(6_000),
            releaseUrl = latest.string("releaseUrl") ?: "$REPOSITORY_URL/releases/latest",
            sourceApkUrl = latest.string("downloadUrl"), mirrorApkUrl = null,
            digestSha256 = latest.string("sha256"), checkedVia = latest.string("checkedVia") ?: "口袋小印更新服务",
        )
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5_000; c.readTimeout = 7_000; c.instanceFollowRedirects = true; c.requestMethod = "GET"
            c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("User-Agent", "xyprt-android/${BuildConfig.VERSION_NAME}")
            val code = c.responseCode; if (code !in 200..299) error("更新服务暂时不可用（HTTP $code）")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }
}

internal fun parseGatewayUpdate(raw: String, json: Json): UpdateInfo? {
    val root = json.parseToJsonElement(raw).jsonObject
    val ok = root.localString("ok")?.toBooleanStrictOrNull() ?: false
    if (!ok) error(root.localString("message") ?: "更新服务返回错误")
    val latest = root["latest"]?.jsonObject ?: return null
    val version = latest.localString("version") ?: return null
    val releaseUrl = latest.localString("releaseUrl") ?: UpdateRepository.REPOSITORY_URL + "/releases/latest"
    return UpdateInfo(
        versionName = version,
        versionCode = latest.localString("versionCode")?.toIntOrNull() ?: semanticVersionCode(version),
        title = latest.localString("title") ?: "口袋小印 $version",
        notes = latest.localString("notes").orEmpty().trim().take(6_000),
        releaseUrl = releaseUrl,
        sourceApkUrl = latest.localString("downloadUrl"), mirrorApkUrl = null,
        digestSha256 = latest.localString("sha256"), checkedVia = latest.localString("checkedVia") ?: "口袋小印更新服务",
    )
}

private fun JsonObject.localString(key: String): String? = runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

fun semanticVersionCode(raw: String): Int {
    val nums = raw.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val major = nums.getOrElse(0) { 0 }.coerceIn(0, 99); val minor = nums.getOrElse(1) { 0 }.coerceIn(0, 99); val patch = nums.getOrElse(2) { 0 }.coerceIn(0, 99)
    return major * 1_000_000 + minor * 10_000 + patch * 100
}
