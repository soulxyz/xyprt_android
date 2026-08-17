package io.github.soulxyz.xyprt

import android.app.Application
import android.content.Context
import io.github.soulxyz.xyprt.render.FontRegistry
import io.github.soulxyz.xyprt.ui.editor.lastSymbolTab
import io.github.soulxyz.xyprt.ui.info.wrapWithAppLanguage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class App : Application() {

    lateinit var container: AppContainer
        private set

    // Before Android 13, also apply the selected app language to the application context,
    // so that getString() in the data/BLE layer uses the app language (from API 33: platform).
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(wrapWithAppLanguage(base))
    }

    override fun onCreate() {
        super.onCreate()
        // Bundled fallbacks are ready before the remote cache restores optional downloaded fonts.
        FontRegistry.init(this)
        container = AppContainer(this)
        // Load the most recently used symbol/emoji tab from the settings into the cache.
        container.applicationScope.launch {
            lastSymbolTab = container.settings.lastSymbolTab.first()
        }
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::container.isInitialized && level >= TRIM_MEMORY_UI_HIDDEN) {
            // Enhanced inference sessions are intentionally lazy. Releasing them here avoids
            // keeping tens of MB alive when the app is in the background; the encrypted model
            // remains on disk and can be reloaded on the next scan.
            container.scanner.releaseEnhanced()
        }
    }

}
