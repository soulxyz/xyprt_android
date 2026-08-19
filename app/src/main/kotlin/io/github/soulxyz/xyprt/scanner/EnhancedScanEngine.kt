package io.github.soulxyz.xyprt.scanner

import android.graphics.Bitmap
import io.github.soulxyz.xyprt.data.remote.EnhancedModelRepository

/** Stable public boundary. Advanced model/runtime details belong to the private provider. */
interface EnhancedScanEngine {
    suspend fun detect(bitmap: Bitmap): EnhancedScanProposal?
    fun release() = Unit
}

data class EnhancedScanProposal(
    val quad: DocumentQuad,
    val confidence: Float,
)

/** Public fallback: DocumentScanner still owns OpenCV detection/refinement and manual adjustment. */
object BasicEnhancedScanEngine : EnhancedScanEngine {
    override suspend fun detect(bitmap: Bitmap): EnhancedScanProposal? = null
}

/** The concrete provider is resolved at compile time by the selected edition. */
object EnhancedScanEngineFactory {
    fun create(models: EnhancedModelRepository): EnhancedScanEngine = EnhancedScanEngineProvider.create(models)
}
