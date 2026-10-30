package app.tvcouchbrowser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The phone-pairing token: 128 bits from SecureRandom, kept in SharedPreferences encrypted with an
 * AES-GCM key that lives in the Android Keystore and never leaves it.
 */
class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    @Volatile
    var token: String = load() ?: rotate()
        private set

    /** Makes a new token, so every phone paired before has to scan the QR code again. */
    fun rotate(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val t = bytes.joinToString("") { "%02x".format(it) }
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = cipher.iv + cipher.doFinal(t.toByteArray())
        prefs.edit().putString("token", Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        token = t
        return t
    }

    /** Constant-time comparison with the current token. */
    fun matches(candidate: String?): Boolean {
        if (candidate == null) return false
        return MessageDigest.isEqual(candidate.toByteArray(), token.toByteArray())
    }

    private fun load(): String? = try {
        prefs.getString("token", null)?.let {
            val blob = Base64.decode(it, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
            String(cipher.doFinal(blob, 12, blob.size - 12))
        }
    } catch (e: Exception) {
        null
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        private const val ALIAS = "tvcb_pairing"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
