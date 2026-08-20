package io.github.soulxyz.xyprt.data.remote

import android.content.Context
import android.os.Build
import io.github.soulxyz.xyprt.BuildConfig
import io.github.soulxyz.xyprt.security.ReleaseContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class CoCreatorState(
    val active: Boolean = false,
    val expiresAt: Long? = null,
    val label: String? = null,
    val refreshing: Boolean = false,
    val lastError: String? = null,
    val recoveryRequired: Boolean = false,
    val entryEnabled: Boolean = false,
    val planMarkdown: String = "",
    val planBadge: String = "小范围开放",
) {
    /** Distribution channel is a build property, never derived from local entitlement. */
    val editionLabel: String get() = ReleaseContract.channelLabel
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

    /**
     * Ensures the device authentication key is actually bound before any protected endpoint is
     * used. installationId alone is never treated as authentication.
     */
    suspend fun registerDevice() {
        reconcilePendingKeyOperation()
        val init = api.postJson("/v1/device/challenge.php", buildJsonObject {
            put("installationId", identity.installationId)
            put("purpose", "register")
        })
        val challenge = init["challenge"]?.jsonObject ?: error("设备认证初始化失败")
        if (challenge.boolean("alreadyBound") == true) {
            try {
                api.signedPost("/v1/device/register.php", deviceBody(identity.currentKeyMaterial()))
            } catch (t: Throwable) {
                if (!isRecoverableDeviceAuthFailure(t)) throw t
                try {
                    repairDeviceAuth()
                    api.signedPost("/v1/device/register.php", deviceBody(identity.currentKeyMaterial()))
                } catch (repairFailure: Throwable) {
                    if (isRecoverableDeviceAuthFailure(repairFailure)) {
                        _state.value = _state.value.copy(recoveryRequired = true, lastError = repairFailure.message)
                    }
                    throw repairFailure
                }
            }
            return
        }

        val version = challenge.int("keyVersion") ?: identity.keyVersion
        val material = if (version == identity.keyVersion) identity.currentKeyMaterial() else identity.prepareRotation(version)
        val challengeText = when (challenge.string("proofMode")) {
            "rsa_unwrap" -> identity.unwrapRegistrationChallenge(
                challenge.string("wrappedChallenge") ?: error("设备认证挑战为空"),
                identity.keyVersion,
            )
            else -> challenge.string("challenge") ?: error("设备认证挑战为空")
        }
        val challengeId = challenge.string("challengeId") ?: error("设备认证挑战编号为空")
        val signature = identity.signChallenge(
            purpose = "register",
            challengeId = challengeId,
            challenge = challengeText,
            version = version,
            encryptionKeyFingerprint = material.encryptionKeyFingerprint,
            authKeyFingerprint = material.authKeyFingerprint,
        )
        api.postJson("/v1/device/register-complete.php", buildJsonObject {
            deviceFields(this, material)
            put("challengeId", challengeId)
            put("challengeSignature", signature)
        })
        // Never delete a future key merely because the response was lost. The server may already
        // have committed. A later DeviceAuth failure goes through explicit in-app recovery.
        if (version > identity.keyVersion) identity.commitRotation(version)
    }

    suspend fun refresh(silent: Boolean = false) {
        if (!silent) _state.value = _state.value.copy(refreshing = true, lastError = null)
        runCatching {
            registerDevice()
            val root = api.signedGet("/v1/sponsor/status.php")
            parseSponsor(root["sponsor"]?.jsonObject, root["ui"]?.jsonObject)
        }.onSuccess { _state.value = it; cache(it) }
            .onFailure { t ->
                if (!silent) {
                    _state.value = _state.value.copy(
                        refreshing = false,
                        lastError = t.message,
                        recoveryRequired = isRecoverableDeviceAuthFailure(t) || _state.value.recoveryRequired,
                    )
                }
            }
    }

    suspend fun activate(code: String): Result<CoCreatorState> {
        val clean = code.trim().uppercase()
        if (clean.isBlank()) return Result.failure(IllegalArgumentException("共创码不能为空"))
        return runCatching {
            try {
                registerDevice()
            } catch (t: Throwable) {
                if (isRecoverableDeviceAuthFailure(t)) recoverDevice(clean) else throw t
            }
            val root = api.signedPost("/v1/sponsor/activate.php", buildJsonObject { put("code", clean) })
            parseSponsor(root["sponsor"]?.jsonObject, root["ui"]?.jsonObject).also { _state.value = it; cache(it) }
        }.onFailure { t ->
            if (isRecoverableDeviceAuthFailure(t) || t.message.orEmpty().contains("recovery_review_required", true)) {
                _state.value = _state.value.copy(recoveryRequired = true, lastError = t.message)
            }
        }
    }

    fun deactivate() {
        prefs.edit().clear().apply()
        _state.value = CoCreatorState()
    }

    /**
     * Fast repair for a device whose EC request-signing key drifted while the original RSA
     * Keystore identity is still present. The server challenge is encrypted to that old RSA key,
     * so installationId alone cannot authorize the repair. If RSA is also gone we fall back to the
     * explicit sponsor-code recovery flow below.
     */
    private suspend fun repairDeviceAuth() {
        val existing = identity.pendingKeyOperation
        if (existing != null) {
            when (reconcilePendingKeyOperation()) {
                PendingReconcile.COMMITTED -> return
                PendingReconcile.NOT_APPLIED, PendingReconcile.CONFLICT -> identity.discardPendingKeyOperation(existing)
                PendingReconcile.NONE -> Unit
            }
        }
        val init = api.postJson("/v1/device/challenge.php", buildJsonObject {
            put("installationId", identity.installationId)
            put("purpose", "repair")
        })
        val challenge = init["challenge"]?.jsonObject ?: error("设备身份修复初始化失败")
        val version = challenge.int("keyVersion") ?: error("设备身份修复版本为空")
        require(version > identity.keyVersion) { "设备身份修复版本无效" }
        val wrapped = challenge.string("wrappedChallenge") ?: error("设备身份修复挑战为空")
        val challengeText = identity.unwrapRegistrationChallenge(wrapped, identity.keyVersion)
        val challengeId = challenge.string("challengeId") ?: error("设备身份修复挑战编号为空")
        val (pending, material) = identity.preparePendingKeyOperation("rotation", version)
        val signature = identity.signChallenge(
            purpose = "repair",
            challengeId = challengeId,
            challenge = challengeText,
            version = version,
            encryptionKeyFingerprint = material.encryptionKeyFingerprint,
            authKeyFingerprint = material.authKeyFingerprint,
        )
        api.postJson("/v1/device/repair.php", buildJsonObject {
            deviceFields(this, material)
            put("operationId", pending.operationId)
            put("challengeId", challengeId)
            put("challengeSignature", signature)
        })
        identity.commitPendingKeyOperation(pending)
        _state.value = _state.value.copy(recoveryRequired = false, lastError = null)
    }

    /**
     * Recovery is deliberately separate from normal registration. A freshly entered sponsor code,
     * device signal, rate limit and a new challenge are required; ambiguous cases stay manual.
     */
    private suspend fun recoverDevice(code: String) {
        val before = identity.pendingKeyOperation
        if (before != null) {
            when (reconcilePendingKeyOperation()) {
                PendingReconcile.COMMITTED -> return
                PendingReconcile.NOT_APPLIED -> identity.discardPendingKeyOperation(before)
                PendingReconcile.CONFLICT -> {
                    // This is an explicit, sponsor-code-gated recovery path. Abandoning only the
                    // future pending key is safe; the current active key is never deleted here.
                    identity.discardPendingKeyOperation(before)
                }
                PendingReconcile.NONE -> Unit
            }
        }

        val init = api.postJson("/v1/device/challenge.php", buildJsonObject {
            put("installationId", identity.installationId)
            put("purpose", "recovery")
            put("code", code)
            put("androidIdHash", identity.androidIdHash)
        })
        val challenge = init["challenge"]?.jsonObject ?: error("设备恢复初始化失败")
        val version = challenge.int("keyVersion") ?: error("设备恢复版本为空")
        require(version > identity.keyVersion) { "设备恢复版本无效" }
        val (pending, material) = identity.preparePendingKeyOperation("recovery", version)
        val challengeText = challenge.string("challenge") ?: error("设备恢复挑战为空")
        val challengeId = challenge.string("challengeId") ?: error("设备恢复挑战编号为空")
        val signature = identity.signChallenge(
            purpose = "recovery",
            challengeId = challengeId,
            challenge = challengeText,
            version = version,
            encryptionKeyFingerprint = material.encryptionKeyFingerprint,
            authKeyFingerprint = material.authKeyFingerprint,
        )
        // Never delete the pending key on an HTTP exception: the server may already have committed.
        api.postJson("/v1/device/recover.php", buildJsonObject {
            deviceFields(this, material)
            put("operationId", pending.operationId)
            put("code", code)
            put("challengeId", challengeId)
            put("challengeSignature", signature)
        })
        identity.commitPendingKeyOperation(pending)
    }

    /** Explicit maintenance hook; normal app startup never rotates keys. */
    suspend fun rotateDeviceKeys(): Result<Unit> = runCatching {
        val existing = identity.pendingKeyOperation
        if (existing != null) {
            when (reconcilePendingKeyOperation()) {
                PendingReconcile.COMMITTED -> return@runCatching
                PendingReconcile.CONFLICT -> error("设备密钥变更状态冲突，请先恢复设备身份")
                PendingReconcile.NOT_APPLIED -> {
                    if (existing.purpose != "rotation") error("设备恢复尚未完成")
                }
                PendingReconcile.NONE -> Unit
            }
        }
        registerDevice()
        val pendingNow = identity.pendingKeyOperation
        val (pending, material) = if (pendingNow?.purpose == "rotation") {
            pendingNow to identity.keyMaterial(pendingNow.version)
        } else {
            identity.preparePendingKeyOperation("rotation", identity.keyVersion + 1)
        }
        // The operation id makes a retry idempotent; an exception leaves the pending key intact.
        api.signedPost("/v1/device/rotate.php", buildJsonObject {
            deviceFields(this, material)
            put("operationId", pending.operationId)
        })
        identity.commitPendingKeyOperation(pending)
    }

    private enum class PendingReconcile { NONE, COMMITTED, NOT_APPLIED, CONFLICT }

    /**
     * Resolves the ambiguous-commit window without requiring the old private key. The random
     * operationId is only a reconciliation capability; this endpoint never grants entitlement or
     * returns content keys.
     */
    private suspend fun reconcilePendingKeyOperation(): PendingReconcile {
        val pending = identity.pendingKeyOperation ?: return PendingReconcile.NONE
        val material = identity.keyMaterial(pending.version)
        val root = api.postJson("/v1/device/binding-status.php", buildJsonObject {
            put("installationId", identity.installationId)
            put("operationId", pending.operationId)
            put("purpose", pending.purpose)
            put("keyVersion", pending.version)
            put("authKeyFingerprint", material.authKeyFingerprint)
            put("deviceKeyFingerprint", material.encryptionKeyFingerprint)
        })
        return when (root["binding"]?.jsonObject?.string("state")) {
            "committed" -> {
                identity.commitPendingKeyOperation(pending)
                PendingReconcile.COMMITTED
            }
            "not_applied" -> PendingReconcile.NOT_APPLIED
            else -> PendingReconcile.CONFLICT
        }
    }

    fun deviceBody(material: DeviceIdentity.KeyMaterial = identity.currentKeyMaterial()): JsonObject =
        buildJsonObject { deviceFields(this, material) }

    private fun deviceFields(b: kotlinx.serialization.json.JsonObjectBuilder, material: DeviceIdentity.KeyMaterial) = with(b) {
        put("installationId", identity.installationId)
        put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        put("platform", "android")
        put("appVersion", BuildConfig.VERSION_NAME)
        put("appVersionCode", BuildConfig.VERSION_CODE)
        put("edition", ReleaseContract.buildEdition)
        put("androidIdHash", identity.androidIdHash)
        put("deviceKeyFingerprint", material.encryptionKeyFingerprint)
        put("devicePublicKey", material.encryptionPublicKeyBase64)
        put("authKeyVersion", material.version)
        put("authKeyFingerprint", material.authKeyFingerprint)
        put("authPublicKey", material.authPublicKeyBase64)
    }

    fun isRecoverableDeviceAuthFailure(t: Throwable): Boolean {
        val msg = t.message.orEmpty()
        return listOf(
            "device_signature_invalid",
            "device_key_version_mismatch",
            "device_auth_required",
            "device_encryption_identity_mismatch",
            "legacy_challenge_wrap_failed",
            "device_recovery_required",
            "device encryption key unavailable",
            "device auth key unavailable",
            "device_operation_conflict",
            "device_rotation_conflict",
            "device_repair_conflict",
            "invalid_repair_version",
            "device_recovery_conflict",
            "recovery_review_required",
        ).any { msg.contains(it, ignoreCase = true) }
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

    private fun parseSponsor(o: JsonObject?, ui: JsonObject? = null): CoCreatorState = CoCreatorState(
        active = o?.boolean("active") ?: false,
        expiresAt = o?.long("expiresAt"),
        label = o?.string("label"),
        refreshing = false,
        recoveryRequired = false,
        entryEnabled = ui?.boolean("cocreatorEntryEnabled") ?: _state.value.entryEnabled,
        planMarkdown = ui?.string("planMarkdown") ?: _state.value.planMarkdown,
        planBadge = ui?.string("planBadge")?.ifBlank { "小范围开放" } ?: _state.value.planBadge,
    )
}
