package io.github.soulxyz.xyprt.scanner

import io.github.soulxyz.xyprt.data.remote.EnhancedModelRepository

/** Open-source provider deliberately has no ML runtime or private implementation. */
object EnhancedScanEngineProvider {
    fun create(models: EnhancedModelRepository): EnhancedScanEngine = BasicEnhancedScanEngine

    suspend fun probeRuntime(models: EnhancedModelRepository): EnhancedRuntimeProbeResult =
        EnhancedRuntimeProbeResult(false, "基础识别", "当前构建不包含增强识别运行时。")
}
