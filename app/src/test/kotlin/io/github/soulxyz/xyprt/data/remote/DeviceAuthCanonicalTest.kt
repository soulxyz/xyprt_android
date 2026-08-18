package io.github.soulxyz.xyprt.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceAuthCanonicalTest {
    @Test
    fun `request canonical is stable and lowercases body digest`() {
        val actual = DeviceIdentity.requestCanonical(
            method = "post",
            target = "/v1/models/lease.php?a=1&b=2",
            timestampSeconds = 1_700_000_000L,
            nonce = "abc_DEF-123",
            bodySha256 = "AA".repeat(32),
            installationId = "0123456789abcdef0123456789abcdef",
            keyVersion = 3,
        )
        assertEquals(
            listOf(
                "XYPRT-DEVICE-AUTH-V1",
                "POST",
                "/v1/models/lease.php?a=1&b=2",
                "1700000000",
                "abc_DEF-123",
                "aa".repeat(32),
                "0123456789abcdef0123456789abcdef",
                "3",
            ).joinToString("\n"),
            actual,
        )
    }

    @Test
    fun `challenge canonical binds both public key fingerprints`() {
        val actual = DeviceIdentity.challengeCanonical(
            purpose = "register",
            installationId = "0123456789abcdef0123456789abcdef",
            challengeId = "challenge-1",
            challenge = "random-challenge",
            keyVersion = 1,
            authKeyFingerprint = "BB".repeat(32),
            encryptionKeyFingerprint = "CC".repeat(32),
        )
        assertEquals(
            listOf(
                "XYPRT-DEVICE-CHALLENGE-V1",
                "register",
                "0123456789abcdef0123456789abcdef",
                "challenge-1",
                "random-challenge",
                "1",
                "bb".repeat(32),
                "cc".repeat(32),
            ).joinToString("\n"),
            actual,
        )
    }
}
