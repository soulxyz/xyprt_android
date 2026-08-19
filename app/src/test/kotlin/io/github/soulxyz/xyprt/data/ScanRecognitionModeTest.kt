package io.github.soulxyz.xyprt.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanRecognitionModeTest {
    @Test fun `cocreator defaults to enhanced`() {
        assertEquals(ScanRecognitionMode.ENHANCED, resolveScanRecognitionMode(null, true))
    }

    @Test fun `cocreator can choose basic`() {
        assertEquals(ScanRecognitionMode.BASIC, resolveScanRecognitionMode("basic", true))
    }

    @Test fun `opensource never resolves stale enhanced preference`() {
        assertEquals(ScanRecognitionMode.BASIC, resolveScanRecognitionMode("enhanced", false))
    }
}
