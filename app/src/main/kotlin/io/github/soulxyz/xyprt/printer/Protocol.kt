package io.github.soulxyz.xyprt.printer

/**
 * Beeprt BY-288 / 错题小印 X1 protocol.
 *
 * The same byte protocol is transported over Bluetooth Classic SPP or BLE GATT, depending on
 * the printer revision. This module only describes the protocol and geometry.
 */
object Protocol {
    const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"

    /** Names seen in the original app / sibling firmware families. */
    val DEVICE_NAME_PREFIXES = listOf("Qring", "qring", "BY-288", "BY288", "Beeprt", "FlashToy", "F2")

    // 48 bytes/row = 384 dots, 203 dpi (8 dots/mm).
    const val HEAD_DOTS = 384
    const val DOTS_PER_MM = 8
    const val BYTES_PER_COLUMN = HEAD_DOTS / 8

    // Commands recovered from the original BYPrintPort path.
    val PRINT_START = byteArrayOf(0x10, 0xFF.toByte(), 0xF1.toByte(), 0x02)
    val PRINT_ENABLE_2 = byteArrayOf(0x1F, 0xB2.toByte(), 0x10)
    val PRINT_END = byteArrayOf(0x10, 0xFF.toByte(), 0xF1.toByte(), 0x45)
    val RASTER_GS_V0 = byteArrayOf(0x1D, 0x76, 0x30, 0x00)
    val FORM_FEED = byteArrayOf(0x1D, 0x0C)
    val WAKEUP = ByteArray(12)

    fun density(level: Int): ByteArray {
        require(level in 0..255)
        return byteArrayOf(0x10, 0xFF.toByte(), 0x10, 0x00, level.toByte())
    }

    /** ESC J n: forward feed n dot rows (0..255). */
    fun feedDots(n: Int): ByteArray {
        require(n in 0..255) { "Feed must be 0..255, was $n" }
        return byteArrayOf(0x1B, 0x4A, n.toByte())
    }

    // Queries.
    val QUERY_STATUS = byteArrayOf(0x10, 0xFF.toByte(), 0x40)
    val QUERY_BATTERY = byteArrayOf(0x10, 0xFF.toByte(), 0x50, 0xF1.toByte())
    val QUERY_MODEL = byteArrayOf(0x10, 0xFF.toByte(), 0x20, 0xF0.toByte())
    val QUERY_FIRMWARE = byteArrayOf(0x10, 0xFF.toByte(), 0x20, 0xF1.toByte())
    val QUERY_SERIAL = byteArrayOf(0x10, 0xFF.toByte(), 0x20, 0xF2.toByte())
    val QUERY_HARDWARE = byteArrayOf(0x10, 0xFF.toByte(), 0x20, 0xEF.toByte())
    val QUERY_BT_VERSION = byteArrayOf(0x10, 0xFF.toByte(), 0x30, 0x10)
    val QUERY_BT_NAME = byteArrayOf(0x10, 0xFF.toByte(), 0x30, 0x11)
    val QUERY_BT_MAC = byteArrayOf(0x10, 0xFF.toByte(), 0x30, 0x12)
    val LEARN_GAP = byteArrayOf(0x10, 0xFF.toByte(), 0x03)

    // Safe transfer pacing for the printer's small serial receive buffer.
    const val SPP_CHUNK_SIZE = 2048
    const val SPP_CHUNK_DELAY_MS = 8L
    const val COPY_DELAY_MS = 500L
    const val QUERY_GAP_MS = 30L

    const val DEFAULT_DENSITY = 1
    const val PRE_FEED_DOTS = 10
    const val CONTINUOUS_FEED_DOTS = 100
}
