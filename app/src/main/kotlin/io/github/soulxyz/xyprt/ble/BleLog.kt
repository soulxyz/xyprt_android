package io.github.soulxyz.xyprt.ble

import android.util.Log
import io.github.soulxyz.xyprt.BuildConfig

/** Debug-only BLE diagnostics (tag LaBLErBLE). Never printed in a release build. */
internal fun bleLog(message: String) {
    if (BuildConfig.DEBUG) Log.i("LaBLErBLE", message)
}
