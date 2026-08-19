package io.github.soulxyz.xyprt.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagedFallbackPolicyTest {
    @Test fun sponsorDeviceAuthFailureMustStayVisible() {
        assertFalse(shouldFallbackManagedFailure(coCreatorActive = true, recoverableDeviceAuthFailure = true))
    }

    @Test fun ordinaryNetworkFailureMayUsePublicFallback() {
        assertTrue(shouldFallbackManagedFailure(coCreatorActive = true, recoverableDeviceAuthFailure = false))
    }

    @Test fun communityClientMayUsePublicFallback() {
        assertTrue(shouldFallbackManagedFailure(coCreatorActive = false, recoverableDeviceAuthFailure = true))
    }
}
