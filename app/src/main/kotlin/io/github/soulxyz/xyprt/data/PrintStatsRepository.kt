package io.github.soulxyz.xyprt.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.max
import kotlin.math.roundToLong

@Serializable
data class PrintStatsSnapshot(
    /** Number of successful print actions. One action may contain multiple copies. */
    val printCount: Long = 0,
    /** Physical copies produced across all successful print actions. */
    val copyCount: Long = 0,
    /** Estimated paper length actually printed, in millimetres. */
    val printedLengthMm: Long = 0,
    /** The first recoverable print timestamp, if available. */
    val firstPrintedAt: Long? = null,
    /** False when an old build had already pruned history needed to reconstruct earlier mileage. */
    val mileageComplete: Boolean = true,
)

/**
 * Lifetime print statistics.
 *
 * Older builds only persisted the latest 50 history rows, but kept a monotonically increasing
 * history sequence. On first launch after upgrading we therefore recover the exact historical
 * print-action count from that sequence and recover as much mileage as the retained history can
 * prove. New successful prints are accumulated independently, so deleting recent history does not
 * erase lifetime statistics.
 */
class PrintStatsRepository(
    context: Context,
    private val json: Json,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val legacyPrefs = context.applicationContext.getSharedPreferences(LEGACY_DB_PREFS, Context.MODE_PRIVATE)

    private val initialStats = loadOrMigrate()
    private val _stats = MutableStateFlow(initialStats)
    val stats: StateFlow<PrintStatsSnapshot> = _stats.asStateFlow()

    @Synchronized
    fun recordSuccessfulPrint(copies: Int, printedLengthMm: Double) {
        if (copies <= 0) return
        val current = _stats.value
        val next = current.copy(
            printCount = current.printCount + 1,
            copyCount = current.copyCount + copies,
            printedLengthMm = current.printedLengthMm + printedLengthMm.coerceAtLeast(0.0).roundToLong(),
            firstPrintedAt = current.firstPrintedAt ?: System.currentTimeMillis(),
        )
        persist(next)
    }

    @Synchronized
    fun replace(snapshot: PrintStatsSnapshot) {
        persist(snapshot.copy(
            printCount = snapshot.printCount.coerceAtLeast(0),
            copyCount = snapshot.copyCount.coerceAtLeast(0),
            printedLengthMm = snapshot.printedLengthMm.coerceAtLeast(0),
        ))
    }

    /** Additive import: fold an imported backup snapshot into current lifetime stats. */
    @Synchronized
    fun merge(snapshot: PrintStatsSnapshot) {
        persist(mergeStats(_stats.value, snapshot))
    }

    /** Used when importing an older backup that has history but predates lifetime stats. */
    @Synchronized
    fun replaceFromHistory(entries: List<PrintHistoryEntry>) {
        persist(recoverStatsFromEntries(entries))
    }

    /** Additive import fallback: fold what the imported history can prove into current stats. */
    @Synchronized
    fun mergeHistory(entries: List<PrintHistoryEntry>) {
        if (entries.isEmpty()) return
        merge(recoverStatsFromEntries(entries))
    }

    private fun loadOrMigrate(): PrintStatsSnapshot {
        if (prefs.getBoolean(KEY_INITIALIZED, false)) {
            return PrintStatsSnapshot(
                printCount = prefs.getLong(KEY_PRINT_COUNT, 0L),
                copyCount = prefs.getLong(KEY_COPY_COUNT, 0L),
                printedLengthMm = prefs.getLong(KEY_LENGTH_MM, 0L),
                firstPrintedAt = prefs.getLong(KEY_FIRST_PRINTED_AT, 0L).takeIf { it > 0L },
                mileageComplete = prefs.getBoolean(KEY_MILEAGE_COMPLETE, true),
            )
        }
        val migrated = recoverLegacyPrintStats(
            rawHistory = legacyPrefs.getString(LEGACY_HISTORY_KEY, null),
            historySeq = legacyPrefs.getLong(LEGACY_HISTORY_SEQ_KEY, 0L),
            json = json,
        )
        persistToPreferences(migrated)
        return migrated
    }

    private fun persistToPreferences(value: PrintStatsSnapshot) {
        prefs.edit()
            .putBoolean(KEY_INITIALIZED, true)
            .putLong(KEY_PRINT_COUNT, value.printCount)
            .putLong(KEY_COPY_COUNT, value.copyCount)
            .putLong(KEY_LENGTH_MM, value.printedLengthMm)
            .putLong(KEY_FIRST_PRINTED_AT, value.firstPrintedAt ?: 0L)
            .putBoolean(KEY_MILEAGE_COMPLETE, value.mileageComplete)
            .apply()
    }

    private fun persist(value: PrintStatsSnapshot) {
        persistToPreferences(value)
        _stats.value = value
    }

    companion object {
        private const val PREFS = "xyprt_print_stats_v1"
        private const val KEY_INITIALIZED = "initialized"
        private const val KEY_PRINT_COUNT = "print_count"
        private const val KEY_COPY_COUNT = "copy_count"
        private const val KEY_LENGTH_MM = "printed_length_mm"
        private const val KEY_FIRST_PRINTED_AT = "first_printed_at"
        private const val KEY_MILEAGE_COMPLETE = "mileage_complete"

        private const val LEGACY_DB_PREFS = "labler_local_db_v1"
        private const val LEGACY_HISTORY_KEY = "history"
        private const val LEGACY_HISTORY_SEQ_KEY = "history_seq"
    }
}

internal fun recoverStatsFromEntries(entries: List<PrintHistoryEntry>): PrintStatsSnapshot {
    var copies = 0L
    var length = 0.0
    var first: Long? = null
    entries.forEach { entry ->
        val c = entry.copies.coerceAtLeast(1)
        copies += c
        length += entry.printedLengthMm ?: (entry.spec.lengthMm.toDouble() * c)
        if (entry.printedAt > 0L) first = first?.let { minOf(it, entry.printedAt) } ?: entry.printedAt
    }
    return PrintStatsSnapshot(
        printCount = entries.size.toLong(),
        copyCount = copies,
        printedLengthMm = length.roundToLong(),
        firstPrintedAt = first,
        mileageComplete = entries.size < 50,
    )
}

internal fun mergeStats(current: PrintStatsSnapshot, incoming: PrintStatsSnapshot): PrintStatsSnapshot = PrintStatsSnapshot(
    printCount = current.printCount + incoming.printCount.coerceAtLeast(0),
    copyCount = current.copyCount + incoming.copyCount.coerceAtLeast(0),
    printedLengthMm = current.printedLengthMm + incoming.printedLengthMm.coerceAtLeast(0),
    firstPrintedAt = when {
        current.firstPrintedAt == null -> incoming.firstPrintedAt
        incoming.firstPrintedAt == null -> current.firstPrintedAt
        else -> minOf(current.firstPrintedAt!!, incoming.firstPrintedAt!!)
    },
    mileageComplete = current.mileageComplete && incoming.mileageComplete,
)

/** Removes the contribution of duplicate history rows from an imported snapshot. */
internal fun subtractStats(base: PrintStatsSnapshot, sub: PrintStatsSnapshot): PrintStatsSnapshot = PrintStatsSnapshot(
    printCount = (base.printCount - sub.printCount.coerceAtLeast(0)).coerceAtLeast(0),
    copyCount = (base.copyCount - sub.copyCount.coerceAtLeast(0)).coerceAtLeast(0),
    printedLengthMm = (base.printedLengthMm - sub.printedLengthMm.coerceAtLeast(0)).coerceAtLeast(0),
    firstPrintedAt = base.firstPrintedAt,
    mileageComplete = base.mileageComplete,
)

internal fun recoverLegacyPrintStats(rawHistory: String?, historySeq: Long, json: Json): PrintStatsSnapshot {
    if (rawHistory.isNullOrBlank()) {
        val count = historySeq.coerceAtLeast(0L)
        return PrintStatsSnapshot(printCount = count, mileageComplete = count == 0L)
    }
    val array = runCatching { json.parseToJsonElement(rawHistory) as? JsonArray }.getOrNull() ?: JsonArray(emptyList())
    var copies = 0L
    var lengthMm = 0.0
    var first: Long? = null
    var maxHistoryId = 0L

    array.forEach { element ->
        val o = element as? JsonObject ?: return@forEach
        val historyId = o["id"]?.jsonPrimitive?.longOrNull ?: 0L
        if (historyId > maxHistoryId) maxHistoryId = historyId
        val c = o["copies"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1) ?: 1
        val explicitLength = o["printedLengthMm"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val perCopyLength = o["lengthMm"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1) ?: 1
        copies += c
        lengthMm += explicitLength ?: (perCopyLength.toDouble() * c)
        val printedAt = o["printedAt"]?.jsonPrimitive?.longOrNull ?: 0L
        if (printedAt > 0L) first = first?.let { minOf(it, printedAt) } ?: printedAt
    }

    val recoveredCount = max(max(historySeq.coerceAtLeast(0L), maxHistoryId), array.size.toLong())
    return PrintStatsSnapshot(
        printCount = recoveredCount,
        copyCount = copies,
        printedLengthMm = lengthMm.roundToLong(),
        firstPrintedAt = first,
        mileageComplete = recoveredCount <= array.size.toLong(),
    )
}
