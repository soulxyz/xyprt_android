package io.github.soulxyz.xyprt.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    @Test fun semanticVersionsMapToMonotonicCodes() {
        assertEquals(1_020_000, semanticVersionCode("1.2.0"))
        assertEquals(1_020_100, semanticVersionCode("v1.2.1"))
        assertTrue(semanticVersionCode("1.3.0") > semanticVersionCode("1.2.99"))
    }
}
