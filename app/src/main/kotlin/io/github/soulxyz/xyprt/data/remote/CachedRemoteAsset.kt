package io.github.soulxyz.xyprt.data.remote

import java.io.File
import java.io.InputStream

/**
 * A verified local remote-asset handle. Protected resources remain ciphertext at rest; callers read
 * plaintext through [openInputStream]. Fonts/public resources may expose [plainFile].
 */
sealed interface CachedRemoteAsset {
    val asset: RemoteAsset
    val contentSha256: String
    val contentSize: Long
    val plainFile: File?
    fun openInputStream(): InputStream
}

internal data class PlainCachedRemoteAsset(
    override val asset: RemoteAsset,
    private val file: File,
    override val contentSha256: String,
    override val contentSize: Long,
) : CachedRemoteAsset {
    override val plainFile: File get() = file
    override fun openInputStream(): InputStream = file.inputStream().buffered(128 * 1024)
}

internal class EncryptedCachedRemoteAsset(
    override val asset: RemoteAsset,
    private val encryptedFile: File,
    private val wrappedKey: String,
    private val identity: DeviceIdentity,
    override val contentSha256: String,
    override val contentSize: Long,
) : CachedRemoteAsset {
    override val plainFile: File? = null

    override fun openInputStream(): InputStream {
        val key = identity.unwrapDeviceKey(wrappedKey)
        return try {
            Xya1ResourceCrypto.openVerified(encryptedFile, key, contentSha256, contentSize)
        } finally {
            key.fill(0)
        }
    }
}
