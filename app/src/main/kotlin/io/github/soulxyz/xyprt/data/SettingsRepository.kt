package io.github.soulxyz.xyprt.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class SavedPrinter(val address: String, val name: String, val transport: String? = null)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val PRINTER_ADDRESS = stringPreferencesKey("printer_address")
        val PRINTER_NAME = stringPreferencesKey("printer_name")
        val PRINTER_TRANSPORT = stringPreferencesKey("printer_transport")
        val DEFAULT_TAPE_WIDTH = intPreferencesKey("default_tape_width_mm")
        val DEFAULT_LENGTH = intPreferencesKey("default_length_mm")
        val DEFAULT_DIE_CUT = booleanPreferencesKey("default_die_cut")
        val LAST_SYMBOL_TAB = intPreferencesKey("last_symbol_tab")
        val PRINT_FEED_BEFORE = intPreferencesKey("print_feed_before_dots")
        val PRINT_FEED_AFTER = intPreferencesKey("print_feed_after_dots")
        val LAST_SEEN_UPDATE_CODE = intPreferencesKey("last_seen_update_code")
    }

    val savedPrinter: Flow<SavedPrinter?> = context.dataStore.data.map { prefs ->
        val address = prefs[Keys.PRINTER_ADDRESS] ?: return@map null
        SavedPrinter(address, prefs[Keys.PRINTER_NAME] ?: address, prefs[Keys.PRINTER_TRANSPORT])
    }

    val defaultTapeWidthMm: Flow<Int> = context.dataStore.data.map { it[Keys.DEFAULT_TAPE_WIDTH] ?: 48 }
    val defaultLengthMm: Flow<Int> = context.dataStore.data.map { it[Keys.DEFAULT_LENGTH] ?: 60 }
    val defaultDieCut: Flow<Boolean> = context.dataStore.data.map { it[Keys.DEFAULT_DIE_CUT] ?: false }

    suspend fun savePrinter(address: String, name: String, transport: String? = null) {
        context.dataStore.edit {
            it[Keys.PRINTER_ADDRESS] = address
            it[Keys.PRINTER_NAME] = name
            if (transport != null) it[Keys.PRINTER_TRANSPORT] = transport else it.remove(Keys.PRINTER_TRANSPORT)
        }
    }

    suspend fun forgetPrinter() {
        context.dataStore.edit {
            it.remove(Keys.PRINTER_ADDRESS)
            it.remove(Keys.PRINTER_NAME)
            it.remove(Keys.PRINTER_TRANSPORT)
        }
    }

    suspend fun saveDefaultLabel(tapeWidthMm: Int, lengthMm: Int, dieCut: Boolean) {
        context.dataStore.edit {
            it[Keys.DEFAULT_TAPE_WIDTH] = tapeWidthMm
            it[Keys.DEFAULT_LENGTH] = lengthMm
            it[Keys.DEFAULT_DIE_CUT] = dieCut
        }
    }


    val printFeedBeforeDots: Flow<Int> = context.dataStore.data.map { it[Keys.PRINT_FEED_BEFORE] ?: 10 }
    val printFeedAfterDots: Flow<Int> = context.dataStore.data.map { it[Keys.PRINT_FEED_AFTER] ?: 100 }
    val lastSeenUpdateCode: Flow<Int> = context.dataStore.data.map { it[Keys.LAST_SEEN_UPDATE_CODE] ?: 0 }

    suspend fun savePrintSpacing(beforeDots: Int, afterDots: Int) {
        context.dataStore.edit {
            it[Keys.PRINT_FEED_BEFORE] = beforeDots.coerceIn(0, 240)
            it[Keys.PRINT_FEED_AFTER] = afterDots.coerceIn(0, 320)
        }
    }

    suspend fun markUpdateSeen(versionCode: Int) {
        context.dataStore.edit { prefs ->
            val old = prefs[Keys.LAST_SEEN_UPDATE_CODE] ?: 0
            if (versionCode > old) prefs[Keys.LAST_SEEN_UPDATE_CODE] = versionCode
        }
    }

    /** Last used tab in the symbol/emoji dialog (0 = symbols, 1 = emojis). */
    val lastSymbolTab: Flow<Int> = context.dataStore.data.map { it[Keys.LAST_SYMBOL_TAB] ?: 0 }

    suspend fun saveLastSymbolTab(tab: Int) {
        context.dataStore.edit { it[Keys.LAST_SYMBOL_TAB] = tab }
    }
}
