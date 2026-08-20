package io.github.soulxyz.xyprt.scanner

import android.graphics.Bitmap
import io.github.soulxyz.xyprt.data.ScanRecognitionMode
import io.github.soulxyz.xyprt.data.SettingsRepository
import io.github.soulxyz.xyprt.data.remote.EnhancedModelRepository
import kotlinx.coroutines.flow.first

/** Stable public boundary. Advanced model/runtime details belong to the private provider. */
interface EnhancedScanEngine {
    suspend fun detect(bitmap: Bitmap): EnhancedScanProposal?
    fun release() = Unit
}

data class EnhancedScanProposal(
    val quad: DocumentQuad,
    val confidence: Float,
)

data class EnhancedRuntimeProbeResult(
    val ready: Boolean,
    val title: String,
    val detail: String,
)

/** Optional post-capture image normalizer. Private providers return null until their models are installed. */
interface DocumentImageEnhancer {
    suspend fun enhance(bitmap: Bitmap, useBlackpoint: Boolean): Bitmap?
    fun release() = Unit
}

/** Public fallback: the photo is used as-is, no AI normalization. */
object BasicDocumentImageEnhancer : DocumentImageEnhancer {
    override suspend fun enhance(bitmap: Bitmap, useBlackpoint: Boolean): Bitmap? = null
}

/** Public fallback: DocumentScanner still owns OpenCV detection/refinement and manual adjustment. */
object BasicEnhancedScanEngine : EnhancedScanEngine {
    override suspend fun detect(bitmap: Bitmap): EnhancedScanProposal? = null
}

private class SelectableEnhancedScanEngine(
    private val delegate: EnhancedScanEngine,
    private val settings: SettingsRepository,
) : EnhancedScanEngine {
    override suspend fun detect(bitmap: Bitmap): EnhancedScanProposal? =
        if (settings.scanRecognitionMode.first() == ScanRecognitionMode.ENHANCED) delegate.detect(bitmap) else null

    override fun release() = delegate.release()
}

object EnhancedScanEngineFactory {
    fun create(models: EnhancedModelRepository, settings: SettingsRepository): EnhancedScanEngine =
        SelectableEnhancedScanEngine(EnhancedScanEngineProvider.create(models), settings)

    fun createEnhancer(models: EnhancedModelRepository): DocumentImageEnhancer =
        EnhancedScanEngineProvider.createEnhancer(models)

    suspend fun probeRuntime(models: EnhancedModelRepository): EnhancedRuntimeProbeResult =
        EnhancedScanEngineProvider.probeRuntime(models)
}
