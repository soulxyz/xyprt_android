package io.github.soulxyz.xyprt.data.remote

import android.content.Context
import android.os.Build
import io.github.soulxyz.xyprt.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class CoCreatorState(
    val active: Boolean = false,
    val expiresAt: Long? = null,
    val label: String? = null,
    val refreshing: Boolean = false,
    val lastError: String? = null,
    val entryEnabled: Boolean = false,
    val planMarkdown: String = "",
    val planBadge: String = "小范围开放",
) {
    val editionLabel: String get() = if (active) "共创版" else "稳定版"
}

class CoCreatorRepository(
    private val context: Context,
    private val api: ServerApi,
    private val identity: DeviceIdentity,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("xyprt_cocreator_state", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(loadCached())
    val state: StateFlow<CoCreatorState> = _state

    init { scope.launch { refresh(silent = true) } }

    suspend fun registerDevice() {
        api.postJson("/v1/device/register.php", deviceBody())
    }

    suspend fun refresh(silent: Boolean = false) {
        if (!silent) _state.value = _state.value.copy(refreshing = true, lastError = null)
        runCatching {
            registerDevice()
            val root = api.getJson("/v1/sponsor/status.php?installationId=${identity.installationId}")
            parseSponsor(root["sponsor"]?.jsonObject, root["ui"]?.jsonObject)
        }.onSuccess { _state.value = it; cache(it) }
            .onFailure { if (!silent) _state.value = _state.value.copy(refreshing = false, lastError = it.message) }
    }

    suspend fun activate(code: String): Result<CoCreatorState> {
        suspend fun request(): CoCreatorState {
            val root = api.postJson("/v1/sponsor/activate.php", buildJsonObject {
                deviceFields(this)
                put("code", code.trim())
            })
            return parseSponsor(root["sponsor"]?.jsonObject, root["ui"]?.jsonObject).also { _state.value = it; cache(it) }
        }
        return try {
            Result.success(request())
        } catch (first: Throwable) {
            if (first.message.orEmpty().contains("device_identity_mismatch", ignoreCase = true)) {
                identity.rotateInstallationId()
                runCatching { request() }
            } else Result.failure(first)
        }
    }

    fun deviceBody() = buildJsonObject { deviceFields(this) }

    private fun deviceFields(b: kotlinx.serialization.json.JsonObjectBuilder) = with(b) {
        put("installationId", identity.installationId)
        put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        put("platform", "android")
        put("appVersion", BuildConfig.VERSION_NAME)
        put("appVersionCode", BuildConfig.VERSION_CODE)
        put("edition", if (_state.value.active) "cocreator" else "community")
        put("androidIdHash", identity.androidIdHash)
        put("deviceKeyFingerprint", identity.publicKeyFingerprint)
        put("devicePublicKey", identity.publicKeyBase64)
    }

    private fun loadCached(): CoCreatorState {
        val exp = prefs.getLong("expires_at", 0L).takeIf { it > 0L }
        val active = prefs.getBoolean("active", false) && (exp == null || exp > System.currentTimeMillis() / 1000L)
        return CoCreatorState(
            active = active,
            expiresAt = exp,
            label = prefs.getString("label", null),
            entryEnabled = prefs.getBoolean("entry_enabled", false),
            planMarkdown = prefs.getString("plan_markdown", "").orEmpty(),
            planBadge = prefs.getString("plan_badge", "小范围开放").orEmpty().ifBlank { "小范围开放" },
        )
    }

    private fun cache(state: CoCreatorState) {
        prefs.edit()
            .putBoolean("active", state.active)
            .putLong("expires_at", state.expiresAt ?: 0L)
            .putString("label", state.label)
            .putBoolean("entry_enabled", state.entryEnabled)
            .putString("plan_markdown", state.planMarkdown)
            .putString("plan_badge", state.planBadge)
            .apply()
    }

    private fun parseSponsor(o: kotlinx.serialization.json.JsonObject?, ui: kotlinx.serialization.json.JsonObject? = null): CoCreatorState {
        return CoCreatorState(
            active = o?.boolean("active") ?: false,
            expiresAt = o?.long("expiresAt"),
            label = o?.string("label"),
            refreshing = false,
            entryEnabled = ui?.boolean("cocreatorEntryEnabled") ?: _state.value.entryEnabled,
            planMarkdown = ui?.string("planMarkdown") ?: _state.value.planMarkdown,
            planBadge = ui?.string("planBadge")?.ifBlank { "小范围开放" } ?: _state.value.planBadge,
        )
    }
}
