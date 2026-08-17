package io.github.soulxyz.xyprt.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {
    @Test fun `fallback matches known bluetooth names and current raster contract`() {
        val p = DeviceProfile.BY288_FALLBACK
        assertTrue(p.matchesBluetoothName("BY-288_1234"))
        assertTrue(p.matchesBluetoothName("qring-x1"))
        assertTrue(p.isCurrentDriverCompatible())
    }

    @Test fun `remote profile cannot silently change raster width under current driver`() {
        val incompatible = DeviceProfile.BY288_FALLBACK.copy(
            revision = 9,
            profile = DeviceProfile.BY288_FALLBACK.profile.copy(
                print = DeviceProfile.BY288_FALLBACK.profile.print.copy(printableWidthDots = 576),
            ),
        )
        assertFalse(incompatible.isCurrentDriverCompatible())
    }
}
