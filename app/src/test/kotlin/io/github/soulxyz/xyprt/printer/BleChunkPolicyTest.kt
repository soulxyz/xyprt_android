package io.github.soulxyz.xyprt.printer

import org.junit.Assert.assertEquals
import org.junit.Test

class BleChunkPolicyTest {
    @Test fun androidBleStartsConservatively() {
        assertEquals(20, Protocol.BLE_INITIAL_CHUNK_SIZE)
    }

    @Test fun invalidLengthFallsBackTwentyToEight() {
        assertEquals(16, Protocol.nextBleChunkSize(20))
        assertEquals(12, Protocol.nextBleChunkSize(16))
        assertEquals(8, Protocol.nextBleChunkSize(12))
        assertEquals(8, Protocol.nextBleChunkSize(8))
    }
}
