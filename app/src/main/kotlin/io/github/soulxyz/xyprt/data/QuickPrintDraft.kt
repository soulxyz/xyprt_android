package io.github.soulxyz.xyprt.data

import kotlinx.serialization.Serializable

/** Recoverable quick-print editing session. Sources are copied into app-private storage. */
@Serializable
data class QuickPrintDraft(
    val source: QuickPrintHistorySource,
    val showCropEditor: Boolean = false,
    val imageCorrectionApplied: Boolean = false,
    val savedAt: Long = System.currentTimeMillis(),
)
