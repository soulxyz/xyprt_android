package io.github.soulxyz.xyprt.device

import io.github.soulxyz.xyprt.printer.Protocol
import kotlinx.serialization.Serializable

@Serializable
data class DeviceMatching(
    val bluetoothNamePrefixes: List<String> = emptyList(),
)

@Serializable
data class PrintCapabilities(
    val dpiX: Int = 203,
    val dpiY: Int = 203,
    val dotsPerMm: Int = 8,
    val printableWidthDots: Int = Protocol.HEAD_DOTS,
    val marginLeftDots: Int = 0,
    val marginRightDots: Int = 0,
    val densityMin: Int = 0,
    val densityMax: Int = 255,
    val defaultDensity: Int = Protocol.DEFAULT_DENSITY,
)

@Serializable
data class PaperCapabilities(
    val modes: List<String> = listOf("continuous"),
    val defaultMode: String = "continuous",
)

@Serializable
data class TransportCapabilities(
    val ble: Boolean = true,
    val classicSpp: Boolean = true,
)

@Serializable
data class PrinterFeatureCapabilities(
    val statusQuery: Boolean = true,
    val batteryQuery: Boolean = true,
    val modelQuery: Boolean = true,
    val firmwareQuery: Boolean = true,
    val serialQuery: Boolean = true,
    val hardwareQuery: Boolean = true,
    val gapLearning: Boolean = true,
    val rasterCompression: Boolean = false,
)

@Serializable
data class DeviceProfileBody(
    val schemaVersion: Int = 1,
    val print: PrintCapabilities = PrintCapabilities(),
    val paper: PaperCapabilities = PaperCapabilities(),
    val transport: TransportCapabilities = TransportCapabilities(),
    val capabilities: PrinterFeatureCapabilities = PrinterFeatureCapabilities(),
)

@Serializable
data class DeviceProfile(
    val id: String,
    val name: String,
    val revision: Int,
    val matching: DeviceMatching,
    val profile: DeviceProfileBody,
) {
    fun matchesBluetoothName(name: String): Boolean = matching.bluetoothNamePrefixes.any { name.startsWith(it, ignoreCase = true) }

    /** Current native driver can consume only this exact raster geometry/protocol family. */
    fun isCurrentDriverCompatible(): Boolean =
        profile.schemaVersion == 1 &&
            profile.print.printableWidthDots == Protocol.HEAD_DOTS &&
            profile.print.dotsPerMm == Protocol.DOTS_PER_MM &&
            (profile.transport.ble || profile.transport.classicSpp)

    companion object {
        val BY288_FALLBACK = DeviceProfile(
            id = "by288_v1",
            name = "BY-288 / X1",
            revision = 0,
            matching = DeviceMatching(Protocol.DEVICE_NAME_PREFIXES),
            profile = DeviceProfileBody(),
        )
    }
}
