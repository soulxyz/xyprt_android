package io.github.toolicious.labler.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight local store used by the BY-288 fork.
 *
 * Upstream LaBLEr uses Room/KSP. The portable offline toolchain available for this
 * revival build deliberately avoids Room code generation so the app can be built
 * reproducibly with Kotlin 2.0.20. The repository-facing DAO API is preserved,
 * therefore the rest of the app (templates/history/backup/editor) stays unchanged.
 */
data class TemplateEntity(
    val id: String,
    val name: String,
    val tapeWidthMm: Int,
    val lengthMm: Int,
    val media: String,
    val elementsJson: String,
    val schemaVersion: Int,
    val favorite: Boolean,
    val counterValue: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

interface TemplateDao {
    fun observeAll(): Flow<List<TemplateEntity>>
    suspend fun getById(id: String): TemplateEntity?
    suspend fun upsert(entity: TemplateEntity)
    suspend fun setFavorite(id: String, favorite: Boolean)
    suspend fun rename(id: String, name: String, updatedAt: Long)
    suspend fun setCounter(id: String, value: Int)
    suspend fun delete(id: String)
    suspend fun getAllOnce(): List<TemplateEntity>
    suspend fun deleteAll()
}

data class PrintHistoryEntity(
    val id: Long = 0,
    val templateId: String?,
    val templateName: String,
    val tapeWidthMm: Int,
    val lengthMm: Int,
    val media: String,
    val elementsJson: String,
    val copies: Int,
    val printedAt: Long,
)

interface PrintHistoryDao {
    fun observeAll(): Flow<List<PrintHistoryEntity>>
    suspend fun insert(entry: PrintHistoryEntity)
    suspend fun prune()
    suspend fun delete(id: Long)
    suspend fun clear()
}

class LocalDatabase(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("labler_local_db_v1", Context.MODE_PRIVATE)
    private val lock = Any()

    val templateDao: TemplateDao = PrefTemplateDao(prefs, lock)
    val printHistoryDao: PrintHistoryDao = PrefHistoryDao(prefs, lock)
}

private const val KEY_TEMPLATES = "templates"
private const val KEY_HISTORY = "history"
private const val KEY_HISTORY_SEQ = "history_seq"

private class PrefTemplateDao(
    private val prefs: android.content.SharedPreferences,
    private val lock: Any,
) : TemplateDao {
    private val state = MutableStateFlow(load())

    override fun observeAll(): Flow<List<TemplateEntity>> = state

    override suspend fun getById(id: String): TemplateEntity? = synchronized(lock) {
        state.value.firstOrNull { it.id == id }
    }

    override suspend fun upsert(entity: TemplateEntity) = synchronized(lock) {
        val list = state.value.toMutableList()
        val i = list.indexOfFirst { it.id == entity.id }
        if (i >= 0) list[i] = entity else list += entity
        publish(list)
    }

    override suspend fun setFavorite(id: String, favorite: Boolean) = synchronized(lock) {
        publish(state.value.map { if (it.id == id) it.copy(favorite = favorite) else it })
    }

    override suspend fun rename(id: String, name: String, updatedAt: Long) = synchronized(lock) {
        publish(state.value.map { if (it.id == id) it.copy(name = name, updatedAt = updatedAt) else it })
    }

    override suspend fun setCounter(id: String, value: Int) = synchronized(lock) {
        publish(state.value.map { if (it.id == id) it.copy(counterValue = value) else it })
    }

    override suspend fun delete(id: String) = synchronized(lock) {
        publish(state.value.filterNot { it.id == id })
    }

    override suspend fun getAllOnce(): List<TemplateEntity> = synchronized(lock) { state.value.toList() }

    override suspend fun deleteAll() = synchronized(lock) { publish(emptyList()) }

    private fun publish(raw: List<TemplateEntity>) {
        val sorted = raw.sortedWith(compareByDescending<TemplateEntity> { it.favorite }.thenByDescending { it.updatedAt })
        prefs.edit().putString(KEY_TEMPLATES, encodeTemplates(sorted)).apply()
        state.value = sorted
    }

    private fun load(): List<TemplateEntity> = decodeTemplates(prefs.getString(KEY_TEMPLATES, null))
        .sortedWith(compareByDescending<TemplateEntity> { it.favorite }.thenByDescending { it.updatedAt })
}

private class PrefHistoryDao(
    private val prefs: android.content.SharedPreferences,
    private val lock: Any,
) : PrintHistoryDao {
    private val state = MutableStateFlow(load().sortedByDescending { it.printedAt }.take(50))

    override fun observeAll(): Flow<List<PrintHistoryEntity>> = state

    override suspend fun insert(entry: PrintHistoryEntity) = synchronized(lock) {
        val next = prefs.getLong(KEY_HISTORY_SEQ, 0L) + 1L
        prefs.edit().putLong(KEY_HISTORY_SEQ, next).apply()
        publish(listOf(entry.copy(id = if (entry.id == 0L) next else entry.id)) + state.value)
    }

    override suspend fun prune() = synchronized(lock) { publish(state.value.sortedByDescending { it.printedAt }.take(50)) }

    override suspend fun delete(id: Long) = synchronized(lock) { publish(state.value.filterNot { it.id == id }) }

    override suspend fun clear() = synchronized(lock) { publish(emptyList()) }

    private fun publish(raw: List<PrintHistoryEntity>) {
        val sorted = raw.sortedByDescending { it.printedAt }.take(50)
        prefs.edit().putString(KEY_HISTORY, encodeHistory(sorted)).apply()
        state.value = sorted
    }

    private fun load(): List<PrintHistoryEntity> = decodeHistory(prefs.getString(KEY_HISTORY, null))
}

private fun encodeTemplates(items: List<TemplateEntity>): String {
    val arr = JSONArray()
    items.forEach { e ->
        arr.put(JSONObject().apply {
            put("id", e.id); put("name", e.name); put("tapeWidthMm", e.tapeWidthMm)
            put("lengthMm", e.lengthMm); put("media", e.media); put("elementsJson", e.elementsJson)
            put("schemaVersion", e.schemaVersion); put("favorite", e.favorite); put("counterValue", e.counterValue)
            put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
        })
    }
    return arr.toString()
}

private fun decodeTemplates(raw: String?): List<TemplateEntity> = runCatching {
    if (raw.isNullOrBlank()) return@runCatching emptyList()
    val arr = JSONArray(raw)
    buildList {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            add(TemplateEntity(
                id = o.getString("id"), name = o.optString("name"), tapeWidthMm = o.optInt("tapeWidthMm", 48),
                lengthMm = o.optInt("lengthMm", 60), media = o.optString("media", "CONTINUOUS"),
                elementsJson = o.optString("elementsJson", "[]"), schemaVersion = o.optInt("schemaVersion", 1),
                favorite = o.optBoolean("favorite", false), counterValue = o.optInt("counterValue", 1),
                createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
            ))
        }
    }
}.getOrDefault(emptyList())

private fun encodeHistory(items: List<PrintHistoryEntity>): String {
    val arr = JSONArray()
    items.forEach { e ->
        arr.put(JSONObject().apply {
            put("id", e.id); put("templateId", e.templateId ?: JSONObject.NULL); put("templateName", e.templateName)
            put("tapeWidthMm", e.tapeWidthMm); put("lengthMm", e.lengthMm); put("media", e.media)
            put("elementsJson", e.elementsJson); put("copies", e.copies); put("printedAt", e.printedAt)
        })
    }
    return arr.toString()
}

private fun decodeHistory(raw: String?): List<PrintHistoryEntity> = runCatching {
    if (raw.isNullOrBlank()) return@runCatching emptyList()
    val arr = JSONArray(raw)
    buildList {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            add(PrintHistoryEntity(
                id = o.optLong("id"), templateId = if (o.isNull("templateId")) null else o.optString("templateId"),
                templateName = o.optString("templateName"), tapeWidthMm = o.optInt("tapeWidthMm", 48),
                lengthMm = o.optInt("lengthMm", 60), media = o.optString("media", "CONTINUOUS"),
                elementsJson = o.optString("elementsJson", "[]"), copies = o.optInt("copies", 1),
                printedAt = o.optLong("printedAt"),
            ))
        }
    }
}.getOrDefault(emptyList())
