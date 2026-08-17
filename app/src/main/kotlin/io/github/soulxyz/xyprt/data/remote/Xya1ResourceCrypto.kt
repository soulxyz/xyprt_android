package io.github.soulxyz.xyprt.data.remote

import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reader for the XYA1 protected-resource container.
 *
 * Wire format: `XYA1` (4 bytes) + GCM nonce (12 bytes) + ciphertext + GCM tag (16 bytes).
 * The magic bytes are authenticated as AAD. Plaintext is never materialised here: callers receive
 * a stream which authenticates the GCM tag and verifies the expected plaintext SHA-256 and size.
 */
internal object Xya1ResourceCrypto {
    val MAGIC = byteArrayOf('X'.code.toByte(), 'Y'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte())
    private const val HEADER_SIZE = 16
    private const val KEY_BYTES = 32

    fun openVerified(
        encryptedFile: File,
        key: ByteArray,
        expectedSha256: String,
        expectedSize: Long,
    ): InputStream {
        require(key.size == KEY_BYTES) { "资源密钥长度错误" }
        require(expectedSize >= 0L) { "资源明文大小无效" }
        require(expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "资源明文 SHA-256 无效" }

        val raw = FileInputStream(encryptedFile)
        try {
            val header = ByteArray(HEADER_SIZE)
            readFully(raw, header)
            require(header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "加密资源格式错误" }
            val nonce = header.copyOfRange(MAGIC.size, HEADER_SIZE)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(MAGIC)
            }
            return VerifiedPlaintextInputStream(
                CipherInputStream(raw, cipher),
                expectedSha256 = expectedSha256,
                expectedSize = expectedSize,
            )
        } catch (t: Throwable) {
            raw.close()
            throw t
        }
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val n = input.read(target, offset, target.size - offset)
            if (n < 0) error("加密资源头不完整")
            offset += n
        }
    }
}

/**
 * Drains on close so a caller cannot accidentally skip GCM authentication by only reading a prefix.
 * Once EOF is reached, plaintext size and SHA-256 must also match the server manifest.
 */
private class VerifiedPlaintextInputStream(
    source: InputStream,
    private val expectedSha256: String,
    private val expectedSize: Long,
) : FilterInputStream(source) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var count = 0L
    private var verified = false
    private var closed = false

    override fun read(): Int {
        ensureOpen()
        val value = super.read()
        if (value >= 0) {
            digest.update(value.toByte())
            count++
        } else {
            verifyAtEof()
        }
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        ensureOpen()
        val n = super.read(buffer, offset, length)
        if (n > 0) {
            digest.update(buffer, offset, n)
            count += n
        } else if (n < 0) {
            verifyAtEof()
        }
        return n
    }

    override fun close() {
        if (closed) return
        var failure: Throwable? = null
        if (!verified) {
            try {
                val buffer = ByteArray(128 * 1024)
                while (read(buffer) >= 0) Unit
            } catch (t: Throwable) {
                failure = t
            }
        }
        try {
            super.close()
        } catch (t: Throwable) {
            if (failure == null) failure = t
        } finally {
            closed = true
        }
        if (failure != null) throw failure
    }

    private fun ensureOpen() = check(!closed) { "资源流已关闭" }

    private fun verifyAtEof() {
        if (verified) return
        require(count == expectedSize) { "资源解密大小校验失败（$count / $expectedSize）" }
        val actual = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
        require(actual.equals(expectedSha256, ignoreCase = true)) { "资源解密 SHA-256 校验失败" }
        verified = true
    }
}
