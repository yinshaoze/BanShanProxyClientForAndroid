package com.v2ray.ang.banshan

import android.util.Base64
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Obfuscates the BanShan subscription URL at rest.
 *
 * The key ships inside the APK, so this only keeps the plain subscription URL out of MMKV
 * dumps and exported backups. It is not a security boundary against a reverse-engineered APK.
 */
object BanShanCrypto {
    const val PREFIX = "bs1:"

    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val KEY_SEED = "BanShanProxy-2024-v2rayN-Hidden-Sub"
    private const val IV_LENGTH = 16
    private const val KEY_ALGORITHM = "AES"

    private val random = SecureRandom()
    private val key: ByteArray by lazy {
        MessageDigest.getInstance("SHA-256").digest(KEY_SEED.toByteArray(Charsets.UTF_8))
    }

    fun isEncrypted(value: String?): Boolean = value?.startsWith(PREFIX) == true

    /**
     * Encrypts a subscription URL as `bs1:<iv base64>.<cipher base64>`.
     *
     * @return the encrypted value, or null when the URL is empty or encryption fails.
     */
    fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return null
        return try {
            val iv = ByteArray(IV_LENGTH).also(random::nextBytes)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, KEY_ALGORITHM), IvParameterSpec(iv))
            val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            "$PREFIX${encode(iv)}.${encode(cipherText)}"
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to encrypt BanShan subscription URL", e)
            null
        }
    }

    /**
     * Decrypts a `bs1:` value.
     *
     * @return the plain subscription URL, or null when the value is malformed.
     */
    fun decrypt(value: String?): String? {
        if (value == null || !value.startsWith(PREFIX)) return null
        val body = value.removePrefix(PREFIX)
        val separator = body.indexOf('.')
        if (separator <= 0 || separator == body.lastIndex) return null
        return try {
            val iv = decode(body.substring(0, separator))
            val cipherText = decode(body.substring(separator + 1))
            if (iv.size != IV_LENGTH) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, KEY_ALGORITHM), IvParameterSpec(iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to decrypt BanShan subscription URL", e)
            null
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
}
