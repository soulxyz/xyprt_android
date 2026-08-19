package io.github.soulxyz.xyprt.ble

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterConnectionFailureTest {
    @Test fun `classic broken pipe invalidates stale connected hint`() {
        assertTrue(
            PrinterConnectionFailure.requiresReconnect(
                PrinterTransport.CLASSIC,
                connectedHint = true,
                error = IOException("Broken pipe"),
            )
        )
    }

    @Test fun `classic nested io failure invalidates stale connected hint`() {
        assertTrue(
            PrinterConnectionFailure.requiresReconnect(
                PrinterTransport.CLASSIC,
                connectedHint = true,
                error = IllegalStateException("print write failed", IOException("socket closed")),
            )
        )
    }

    @Test fun `disconnected transport always reconnects`() {
        assertTrue(
            PrinterConnectionFailure.requiresReconnect(
                PrinterTransport.BLE,
                connectedHint = false,
                error = IllegalStateException("write failed"),
            )
        )
    }

    @Test fun `ble length rejection does not imply dead link`() {
        assertFalse(
            PrinterConnectionFailure.requiresReconnect(
                PrinterTransport.BLE,
                connectedHint = true,
                error = IllegalStateException("BLE 写入失败（13）：分包长度不兼容"),
            )
        )
    }
}
