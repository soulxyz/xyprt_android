package io.github.soulxyz.xyprt.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.serialization.json.Json

class UpdateGatewayPayloadTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesGatewayPayload() {
        val info = parseGatewayUpdate(
            """{
              "ok": true,
              "latest": {
                "version": "1.2.3",
                "versionCode": 1020300,
                "title": "错题小印 1.2.3",
                "notes": "**更新**",
                "releaseUrl": "https://github.com/soulxyz/xyprt_android/releases/tag/v1.2.3",
                "downloadUrl": "https://api.xyprt.5am.top/v1/update/download.php?tag=v1.2.3",
                "sha256": "abc",
                "checkedVia": "错题小印更新服务"
              }
            }""".trimIndent(),
            json,
        )!!
        assertEquals("1.2.3", info.versionName)
        assertEquals(1_020_300, info.versionCode)
        assertEquals("https://api.xyprt.5am.top/v1/update/download.php?tag=v1.2.3", info.sourceApkUrl)
        assertNull(info.mirrorApkUrl)
    }
}
