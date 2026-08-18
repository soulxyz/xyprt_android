package io.github.soulxyz.xyprt

import android.content.Context
import io.github.soulxyz.xyprt.ble.PrinterManager
import io.github.soulxyz.xyprt.data.LocalDatabase
import io.github.soulxyz.xyprt.data.BackupRepository
import io.github.soulxyz.xyprt.data.HistoryRepository
import io.github.soulxyz.xyprt.data.PrintStatsRepository
import io.github.soulxyz.xyprt.data.SettingsRepository
import io.github.soulxyz.xyprt.data.SavedDocumentRepository
import io.github.soulxyz.xyprt.data.TemplateJson
import io.github.soulxyz.xyprt.data.TemplateRepository
import io.github.soulxyz.xyprt.data.UpdateRepository
import io.github.soulxyz.xyprt.data.UpdateDownloadManager
import io.github.soulxyz.xyprt.data.remote.CoCreatorRepository
import io.github.soulxyz.xyprt.data.remote.DeviceIdentity
import io.github.soulxyz.xyprt.data.remote.DeviceProfileRepository
import io.github.soulxyz.xyprt.data.remote.RemoteAssetRepository
import io.github.soulxyz.xyprt.data.remote.EnhancedModelRepository
import io.github.soulxyz.xyprt.data.remote.ServerApi
import io.github.soulxyz.xyprt.scanner.DocumentScanner
import io.github.soulxyz.xyprt.security.AppIntegritySignal
import io.github.soulxyz.xyprt.scanner.EnhancedScanEngineFactory
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
    val deviceIdentity = DeviceIdentity(context)
    val appIntegritySignal = AppIntegritySignal(context)
    val serverApi = ServerApi(json, deviceIdentity, appIntegritySignal)
    val coCreator = CoCreatorRepository(context, serverApi, deviceIdentity, applicationScope)
    val remoteAssets = RemoteAssetRepository(context, json, serverApi, deviceIdentity, coCreator, applicationScope)
    val deviceProfiles = DeviceProfileRepository(context, json, serverApi, applicationScope)
    val enhancedModels = EnhancedModelRepository(context, serverApi, deviceIdentity, coCreator, applicationScope)
    private val enhancedScanEngine = EnhancedScanEngineFactory.create(enhancedModels)
    val scanner = DocumentScanner(enhancedScanEngine)
    val templateRepository = TemplateRepository(database.templateDao, json)
    val historyRepository = HistoryRepository(database.printHistoryDao, json)
    val printStats = PrintStatsRepository(context, json)
    val savedDocuments = SavedDocumentRepository(context, json)
    val updates = UpdateRepository(context, settings, json, applicationScope, serverApi, deviceIdentity, coCreator)
    val updateDownloads = UpdateDownloadManager(context, settings, applicationScope, serverApi)
    val templateJson = TemplateJson(json)
    val backup = BackupRepository(templateRepository, historyRepository, printStats, settings, savedDocuments, json)
    val printerManager = PrinterManager(context, settings, applicationScope)
}
