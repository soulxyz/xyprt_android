package io.github.soulxyz.xyprt.scanner

import android.graphics.Bitmap
import io.github.soulxyz.xyprt.BuildConfig
import io.github.soulxyz.xyprt.data.remote.EnhancedModelRepository

/**
 * Optional scan proposal provider.
 *
 * The open-source build deliberately ships without an ML runtime. It still gets the complete
 * OpenCV scanner/perspective pipeline; a co-creator build may add an implementation at compile
 * time. The implementation only proposes a quad — DocumentScanner keeps ownership of refinement,
 * conflict resolution and the manual-edit fallback.
 */
interface EnhancedScanEngine {
    suspend fun detect(bitmap: Bitmap): EnhancedScanProposal?
    fun release() = Unit
}

data class EnhancedScanProposal(
    val quad: DocumentQuad,
    val confidence: Float,
)

private object NoopEnhancedScanEngine : EnhancedScanEngine {
    override suspend fun detect(bitmap: Bitmap): EnhancedScanProposal? = null
}

object EnhancedScanEngineFactory {
    private const val ONNX_ENGINE_CLASS = "io.github.soulxyz.xyprt.scanner.OnnxEnhancedScanEngine"

    fun create(models: EnhancedModelRepository): EnhancedScanEngine {
        if (!BuildConfig.ENHANCED_SCANNER_AVAILABLE) return NoopEnhancedScanEngine
        return runCatching {
            val klass = Class.forName(ONNX_ENGINE_CLASS)
            val ctor = klass.getConstructor(EnhancedModelRepository::class.java)
            ctor.newInstance(models) as EnhancedScanEngine
        }.getOrElse { NoopEnhancedScanEngine }
    }
}
