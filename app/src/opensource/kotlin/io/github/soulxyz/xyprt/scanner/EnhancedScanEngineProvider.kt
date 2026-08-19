package io.github.soulxyz.xyprt.scanner

import io.github.soulxyz.xyprt.data.remote.EnhancedModelRepository

/** Open-source provider deliberately has no ML runtime or private implementation. */
object EnhancedScanEngineProvider {
    fun create(models: EnhancedModelRepository): EnhancedScanEngine = BasicEnhancedScanEngine
}
