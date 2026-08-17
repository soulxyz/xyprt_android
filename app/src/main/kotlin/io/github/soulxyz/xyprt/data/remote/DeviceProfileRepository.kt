package io.github.soulxyz.xyprt.data.remote

import android.content.Context
import io.github.soulxyz.xyprt.BuildConfig
import io.github.soulxyz.xyprt.device.DeviceProfile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

@Serializable
private data class DeviceProfileSnapshot(
    val revision: Int = 0,
    val items: List<DeviceProfile> = listOf(DeviceProfile.BY288_FALLBACK),
)

data class DeviceProfileCatalogState(
    val revision: Int = 0,
    val items: List<DeviceProfile> = listOf(DeviceProfile.BY288_FALLBACK),
    val refreshing: Boolean = false,
    val lastError: String? = null,
)

/** Server-updatable capability catalog with a permanent local BY-288 fallback. */
class DeviceProfileRepository(
    context: Context,
    private val json: Json,
    private val api: ServerApi,
    private val scope: CoroutineScope,
) {
    private val file = File(context.filesDir, "device-profiles-v1.json")
    private var snapshot = load()
    private val _state = MutableStateFlow(DeviceProfileCatalogState(snapshot.revision, withFallback(snapshot.items)))
    val state: StateFlow<DeviceProfileCatalogState> = _state

    init { scope.launch { refresh(silent = true) } }

    suspend fun refresh(silent: Boolean = false) {
        if (!silent) _state.value = _state.value.copy(refreshing = true, lastError = null)
        runCatching {
            val root = api.getJson("/v1/device-profiles/manifest.php?appVersionCode=${BuildConfig.VERSION_CODE}&sinceRevision=${snapshot.revision}")
            applyManifest(root["manifest"]?.jsonObject ?: error("设备能力清单为空"))
        }.onSuccess { next ->
            snapshot = next
            save(next)
            _state.value = DeviceProfileCatalogState(next.revision, withFallback(next.items))
        }.onFailure { e -> if (!silent) _state.value = _state.value.copy(refreshing = false, lastError = e.message) }
    }

    fun forBluetoothName(name: String): DeviceProfile? = _state.value.items
        .filter { it.isCurrentDriverCompatible() }
        .sortedByDescending { it.revision }
        .firstOrNull { it.matchesBluetoothName(name) }

    fun byId(id: String): DeviceProfile? = _state.value.items.firstOrNull { it.id == id }

    /** Remote BY-288 metadata can refine non-geometry capabilities but can never change this driver's raster width. */
    fun currentBy288(): DeviceProfile = byId(DeviceProfile.BY288_FALLBACK.id)
        ?.takeIf { it.isCurrentDriverCompatible() }
        ?: DeviceProfile.BY288_FALLBACK

    private fun applyManifest(m: kotlinx.serialization.json.JsonObject): DeviceProfileSnapshot {
        val revision = m.int("revision") ?: snapshot.revision
        val reset = m.boolean("resetRequired") ?: false
        val map = if (reset) linkedMapOf() else snapshot.items.filterNot { it.revision == 0 }.associateByTo(linkedMapOf()) { it.id }
        val upserts = if (reset) m["items"]?.jsonArray ?: JsonArray(emptyList()) else m["upserts"]?.jsonArray ?: JsonArray(emptyList())
        for (e in upserts) runCatching { json.decodeFromJsonElement(DeviceProfile.serializer(), e) }.getOrNull()?.let { map[it.id] = it }
        if (!reset) for (e in m["deletions"]?.jsonArray ?: JsonArray(emptyList())) map.remove(runCatching { e.toString().trim('"') }.getOrDefault(""))
        return DeviceProfileSnapshot(revision, map.values.toList())
    }

    private fun withFallback(items: List<DeviceProfile>): List<DeviceProfile> {
        val safe = items.filter { it.id != DeviceProfile.BY288_FALLBACK.id || it.isCurrentDriverCompatible() }.toMutableList()
        if (safe.none { it.id == DeviceProfile.BY288_FALLBACK.id }) safe.add(DeviceProfile.BY288_FALLBACK)
        return safe.sortedBy { it.id }
    }

    private fun load(): DeviceProfileSnapshot = runCatching {
        if (!file.isFile) return@runCatching DeviceProfileSnapshot()
        json.decodeFromString<DeviceProfileSnapshot>(file.readText())
    }.getOrDefault(DeviceProfileSnapshot())

    private fun save(value: DeviceProfileSnapshot) = runCatching {
        val tmp = File(file.parentFile, file.name + ".new")
        tmp.writeText(json.encodeToString(value))
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
    }.getOrNull()
}
