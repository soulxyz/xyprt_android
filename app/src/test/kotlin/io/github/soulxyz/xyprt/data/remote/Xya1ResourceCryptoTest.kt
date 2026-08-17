package io.github.soulxyz.xyprt.data.remote

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Xya1ResourceCryptoTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `valid xya1 decrypts and verifies`() {
        val plain = ByteArray(512 * 1024 + 17).also(SecureRandom()::nextBytes)
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val file = writeXya1(plain, key)

        val decoded = Xya1ResourceCrypto.openVerified(file, key, sha256(plain), plain.size.toLong()).use { it.readBytes() }
        assertArrayEquals(plain, decoded)
    }

    @Test fun `tampered gcm tag is rejected even when caller closes after prefix`() {
        val plain = "protected-resource".repeat(10_000).toByteArray()
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val file = writeXya1(plain, key)
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        assertThrows(Throwable::class.java) {
            Xya1ResourceCrypto.openVerified(file, key, sha256(plain), plain.size.toLong()).use { input ->
                input.read(ByteArray(64))
                // use/close must drain and authenticate the unread suffix/tag.
            }
        }
    }

    @Test fun `wrong plaintext hash is rejected`() {
        val plain = "hash-check".repeat(2000).toByteArray()
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val file = writeXya1(plain, key)
        val wrong = "00".repeat(32)

        assertThrows(IllegalArgumentException::class.java) {
            Xya1ResourceCrypto.openVerified(file, key, wrong, plain.size.toLong()).use { it.readBytes() }
        }
    }

    private fun writeXya1(plain: ByteArray, key: ByteArray): File {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(Xya1ResourceCrypto.MAGIC)
        }
        val file = temp.newFile("asset.xya1")
        file.outputStream().use { out ->
            out.write(Xya1ResourceCrypto.MAGIC)
            out.write(nonce)
            out.write(cipher.doFinal(plain))
        }
        return file
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
