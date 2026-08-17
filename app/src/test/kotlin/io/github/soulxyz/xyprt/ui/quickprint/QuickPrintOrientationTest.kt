package io.github.soulxyz.xyprt.ui.quickprint

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickPrintOrientationTest {
    @Test fun landscapeIsIndependentFromImageCorrectionRotation() {
        assertEquals(90, QuickImageAdjustments(rotationDegrees = 0, landscapePrint = true).outputRotationDegrees())
        assertEquals(180, QuickImageAdjustments(rotationDegrees = 90, landscapePrint = true).outputRotationDegrees())
        assertEquals(90, QuickImageAdjustments(rotationDegrees = 90, landscapePrint = false).outputRotationDegrees())
    }
}
