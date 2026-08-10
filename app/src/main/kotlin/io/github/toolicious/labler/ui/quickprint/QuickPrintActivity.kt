package io.github.toolicious.labler.ui.quickprint

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.toolicious.labler.ui.info.wrapWithAppLanguage
import io.github.toolicious.labler.ui.theme.LablerTheme

/** Entry point used by Android/WeChat "打开方式" and Share sheets. */
class QuickPrintActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapWithAppLanguage(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LablerTheme {
                QuickPrintScreen(mode = "auto", onBack = { finish() }, externalIntent = intent)
            }
        }
    }
}
