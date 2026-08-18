package io.github.soulxyz.xyprt.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Best-effort self identity signal for server-side risk correlation only.
 *
 * This is deliberately not an enforcement primitive: a modified/rooted client can patch it. The
 * server may compare the reported signing certificate with deployment configuration and record a
 * Shadow event, but normal authorization remains DeviceAuth + server entitlement.
 */
class AppIntegritySignal(private val context: Context) {
    val packageName: String get() = context.packageName

    val signingCertificateSha256: String? by lazy {
        runCatching {
            val info = if (Build.VERSION.SDK_INT >= 28) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
            val signature = if (Build.VERSION.SDK_INT >= 28) {
                val signing = info.signingInfo ?: return@runCatching null
                // apkContentsSigners describes the certificate(s) that signed this installed APK.
                // Signing history is useful for rotation lineage, but is not the current build identity.
                signing.apkContentsSigners.firstOrNull()
            } else {
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()
            } ?: return@runCatching null
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }
}
