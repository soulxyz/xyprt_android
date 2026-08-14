package io.github.soulxyz.xyprt.data.remote

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.util.UUID
import javax.crypto.Cipher

class DeviceIdentity(private val context: Context) {
    private val prefs = context.getSharedPreferences("xyprt_device_identity", Context.MODE_PRIVATE)
    private val alias = "xyprt_device_identity_rsa_v1"

    val installationId: String by lazy {
        prefs.getString("installation_id", null)?.takeIf { it.length in 16..128 } ?: UUID.randomUUID().toString().replace("-", "").also {
            prefs.edit().putString("installation_id", it).apply()
        }
    }

    val androidIdHash: String by lazy {
        val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        sha256Hex("${context.packageName}:$raw".toByteArray())
    }

    val publicKey: PublicKey get() = requireNotNull(ensureKeyPair().getCertificate(alias)).publicKey
    val publicKeyBase64: String get() = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    val publicKeyFingerprint: String get() = sha256Hex(publicKey.encoded)

    fun unwrapModelKey(wrappedBase64: String): ByteArray {
        val wrapped = Base64.decode(wrappedBase64, Base64.DEFAULT)
        val privateKey = ensureKeyPair().getKey(alias, null)
        return Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding").run {
            init(Cipher.DECRYPT_MODE, privateKey)
            doFinal(wrapped)
        }
    }

    private fun ensureKeyPair(): KeyStore {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT)
                    .setKeySize(2048)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256)
                    .build()
            )
            generator.generateKeyPair()
            ks.load(null)
        }
        return ks
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
