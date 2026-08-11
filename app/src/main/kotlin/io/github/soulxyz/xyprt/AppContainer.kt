package io.github.soulxyz.xyprt

import android.content.Context
import io.github.soulxyz.xyprt.ble.PrinterManager
import io.github.soulxyz.xyprt.data.LocalDatabase
import io.github.soulxyz.xyprt.data.BackupRepository
import io.github.soulxyz.xyprt.data.HistoryRepository
import io.github.soulxyz.xyprt.data.SettingsRepository
import io.github.soulxyz.xyprt.data.TemplateJson
import io.github.soulxyz.xyprt.data.TemplateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json

/** Manual dependency root (deliberately without a DI framework). */
class AppContainer(context: Context) {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
        // Fall back unknown enum values (e.g. removed fonts in old templates) to
        // the property's default value instead of failing.
        coerceInputValues = true
    }

    private val database = LocalDatabase(context)

    val settings = SettingsRepository(context)
    val templateRepository = TemplateRepository(database.templateDao, json)
    val historyRepository = HistoryRepository(database.printHistoryDao, json)
    val templateJson = TemplateJson(json)
    val backup = BackupRepository(templateRepository, settings, json)
    val printerManager = PrinterManager(context, settings, applicationScope)
}
