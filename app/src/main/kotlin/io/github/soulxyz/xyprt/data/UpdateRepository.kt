package io.github.soulxyz.xyprt.data

import android.content.Context
import android.os.Build
import io.github.soulxyz.xyprt.BuildConfig
import io.github.soulxyz.xyprt.data.remote.CoCreatorRepository
import io.github.soulxyz.xyprt.data.remote.DeviceIdentity
import io.github.soulxyz.xyprt.data.remote.ServerApi
import io.github.soulxyz.xyprt.data.remote.boolean
import io.github.soulxyz.xyprt.data.remote.int
import io.github.soulxyz.xyprt.data.remote.long
import io.github.soulxyz.xyprt.data.remote.string
import io.github.soulxyz.xyprt.security.ReleaseContract
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
    val releaseId: Int = 0,
    val sourceApkUrl: String?,
    val mirrorApkUrl: String?,
    val digestSha256: String?,
    val checkedVia: String,
    val fullSizeBytes: Long? = null,
    val serverDownloadMode: ServerDownloadMode = ServerDownloadMode.AUTO,
    val delta: DeltaUpdateInfo? = null,
    val requiresDeviceAuth: Boolean = false,
    val serverInstallAvailable: Boolean = false,
    val releaseChannelLabel: String = "社区版",
    val channel: String? = null,
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
            val managed = runCatching { fetchManaged() }
            val info = managed.recoverCatching { t ->
                // Never hide a broken Sponsor DeviceAuth chain behind a successful public fallback.
                // Otherwise a Cocreator-entitled device looks "up to date" while the protected
                // update request is actually failing. Non-auth/network failures may still fall back.
                if (!shouldFallbackManagedFailure(coCreator.state.value.active, coCreator.isRecoverableDeviceAuthFailure(t))) throw t
                fetchGateway()
            }
            _state.value = info.fold(
                onSuccess = { latest -> resolveAvailable(latest) },
                onFailure = { UpdateState.Error(it.message ?: "检查更新失败") },
            )
        }
    }

    fun checkForChannel(targetChannel: String) {
        scope.launch {
            _state.value = UpdateState.Checking
            val result = runCatching { fetchManaged(requestedChannel = targetChannel) }
            _state.value = result.fold(
                onSuccess = { latest -> resolveAvailable(latest) },
                onFailure = { UpdateState.Error(it.message ?: "检查更新失败") },
            )
        }
    }

    private fun resolveAvailable(latest: UpdateInfo?): UpdateState {
        if (latest == null) return UpdateState.Current(null)
        val switch = isChannelSwitch(latest)
        val versionOk = if (switch) latest.versionCode >= currentVersionCode() else latest.versionCode > currentVersionCode()
        return if (versionOk || latest.serverInstallAvailable) UpdateState.Available(latest) else UpdateState.Current(latest)
    }

    fun currentVersionCode(): Int = BuildConfig.VERSION_CODE

    /**
     * Sponsor releases are gated by DeviceAuth, so the "browser download" button cannot reuse the
     * in-app endpoint (a browser cannot sign the request). Instead the app signs once here to mint
     * a single-use, short-lived browser pass, and returns the URL the external browser should open.
     * Returns null when no browser pass is applicable (e.g. community releases), so callers fall
     * back to the existing URL.
     */
    suspend fun browserDownloadUrl(info: UpdateInfo): String? {
        if (!info.requiresDeviceAuth || info.releaseId <= 0) return null
        val body = buildJsonObject {
            put("releaseId", info.releaseId)
            put("abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
        }
        val root = runCatching { api.signedPost("/v1/app/update-download-browser.php", body) }.getOrNull() ?: return null
        return root.string("browserUrl")?.let(api::absolute)
    }

    private suspend fun fetchManaged(requestedChannel: String = ReleaseContract.channel): UpdateInfo? {
        // Community/public release lookup stays anonymous. Device authentication is only
        // established once a co-creator entitlement is already active locally or the user enters
        // the co-creator flow; installationId is never a public-update credential.
        val body = buildJsonObject {
            put("version", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("buildEdition", ReleaseContract.buildEdition)
            put("channel", requestedChannel)
            put("abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
        }
        val root = if (coCreator.state.value.active) {
            coCreator.registerDevice()
            api.signedPost("/v1/app/update-check.php", body)
        } else {
            api.postJson("/v1/app/update-check.php", body)
        }
        val updateAvailable = root.boolean("updateAvailable") == true
        val release = root["release"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: return null
        val channel = root.string("channel") ?: "opensource"
        val version = release.string("version") ?: return null
        return if (channel == "sponsor") {
            val download = release["download"]?.let { runCatching { it.jsonObject }.getOrNull() }
            val protectedDownload = download?.string("endpoint")?.let(api::absolute)
            UpdateInfo(
                versionName = version,
                versionCode = release.int("versionCode") ?: semanticVersionCode(version),
                title = release.string("title") ?: "口袋小印 $version",
                notes = release.string("notes").orEmpty().trim().take(6_000),
                releaseUrl = REPOSITORY_URL,
                releaseId = release.int("id") ?: 0,
                sourceApkUrl = if (updateAvailable) protectedDownload else null,
                mirrorApkUrl = null,
                digestSha256 = release["download"]?.let { runCatching { it.jsonObject.string("sha256") }.getOrNull() },
                checkedVia = "口袋小印更新服务",
                fullSizeBytes = release["download"]?.let { runCatching { it.jsonObject.long("size") }.getOrNull() },
                serverDownloadMode = parseServerDownloadMode(root.string("downloadMode")),
                delta = if (updateAvailable) parseManagedDelta(root, api) else null,
                requiresDeviceAuth = true,
                serverInstallAvailable = updateAvailable,
                releaseChannelLabel = "共创版",
                channel = channel,
            )
        } else parseOpenSourceRelease(release, parseServerDownloadMode(root.string("downloadMode")), channel)
    }

    private suspend fun fetchGateway(): UpdateInfo? = withContext(Dispatchers.IO) { parseGatewayUpdate(httpGet(API_LATEST), json) }

    private fun parseOpenSourceRelease(latest: JsonObject, mode: ServerDownloadMode = ServerDownloadMode.AUTO, channel: String = "opensource"): UpdateInfo? {
        val version = latest.string("version") ?: return null
        return UpdateInfo(
            versionName = version,
            versionCode = latest.int("versionCode") ?: semanticVersionCode(version),
            title = latest.string("title") ?: "口袋小印 $version",
            notes = latest.string("notes").orEmpty().trim().take(6_000),
            releaseUrl = latest.string("releaseUrl") ?: "$REPOSITORY_URL/releases/latest",
            sourceApkUrl = latest.string("downloadUrl"), mirrorApkUrl = null,
            digestSha256 = latest.string("sha256"), checkedVia = latest.string("checkedVia") ?: "口袋小印更新服务",
            fullSizeBytes = latest.long("size"),
            serverDownloadMode = mode,
            releaseChannelLabel = "社区版",
            channel = channel,
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


internal fun shouldFallbackManagedFailure(coCreatorActive: Boolean, recoverableDeviceAuthFailure: Boolean): Boolean =
    !(coCreatorActive && recoverableDeviceAuthFailure)

internal fun isChannelSwitch(info: UpdateInfo): Boolean {
    val target = info.channel?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
    val current = ReleaseContract.channel.lowercase()
    return channelGroup(target) != channelGroup(current)
}

internal fun channelGroup(c: String): String = when (c.lowercase()) {
    "opensource", "community", "public" -> "public"
    "cocreator", "sponsor", "internal", "beta" -> "private"
    else -> c.lowercase()
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
        fullSizeBytes = latest.localString("size")?.toLongOrNull(),
        serverDownloadMode = parseServerDownloadMode(latest.localString("downloadMode")),
        releaseChannelLabel = "社区版",
    )
}

private fun parseServerDownloadMode(raw: String?): ServerDownloadMode = when (raw?.lowercase()) {
    "internal" -> ServerDownloadMode.INTERNAL
    "external" -> ServerDownloadMode.EXTERNAL
    else -> ServerDownloadMode.AUTO
}

private fun parseManagedDelta(root: JsonObject, api: ServerApi): DeltaUpdateInfo? = runCatching {
    val d = root["delta"]?.jsonObject ?: return@runCatching null
    DeltaUpdateInfo(
        url = api.absolute(d.string("url") ?: return@runCatching null),
        fromVersionCode = d.int("fromVersionCode") ?: return@runCatching null,
        fromApkSha256 = d.string("fromApkSha256") ?: return@runCatching null,
        patchSha256 = d.string("sha256") ?: return@runCatching null,
        patchSize = d.long("size") ?: return@runCatching null,
        resultApkSha256 = d.string("resultApkSha256") ?: return@runCatching null,
    )
}.getOrNull()

private fun JsonObject.localString(key: String): String? = runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

fun semanticVersionCode(raw: String): Int {
    val nums = raw.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val major = nums.getOrElse(0) { 0 }.coerceIn(0, 99); val minor = nums.getOrElse(1) { 0 }.coerceIn(0, 99); val patch = nums.getOrElse(2) { 0 }.coerceIn(0, 99)
    return major * 1_000_000 + minor * 10_000 + patch * 100
}
