package io.github.toolicious.labler.ui.info

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import java.util.Locale

private const val ZH = "zh-CN"

/** Alpha2 is intentionally Chinese-only. */
fun wrapWithAppLanguage(base: Context): Context {
    val locale = Locale.forLanguageTag(ZH)
    Locale.setDefault(locale)
    val config = Configuration(base.resources.configuration)
    config.setLocale(locale)
    config.setLayoutDirection(locale)
    return base.createConfigurationContext(config)
}

fun currentAppLanguageTag(context: Context): String? = ZH

/** Kept for backup compatibility; alpha2 deliberately ignores non-Chinese language requests. */
fun setAppLanguage(context: Context, tag: String?) = Unit

@Composable
fun currentLanguageBadge(): String = "中"
