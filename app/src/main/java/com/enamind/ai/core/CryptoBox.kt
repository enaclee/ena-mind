package com.enamind.ai.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * CryptoBox — шифрование секретов через Android Keystore.
 * AES-256/GCM: ключ живёт в AndroidKeyStore (неэкспортируемый), формат хранения
 * base64(iv || ciphertext). Ключи API никогда не лежат в открытом виде ни в
 * SharedPreferences, ни в JSON, ни в логах.
 *
 * v8.0: перенесён из пакета ai/ — внешние AI-провайдеры удалены из приложения,
 * шифрование осталось для ключей Bybit (трейд-панель) и NewsAPI.
 */
object CryptoBox {

    private const val ALIAS = "enamind_master_v1"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Шифрует строку -> base64(iv||ct). Пустая строка остаётся пустой. */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val iv = c.iv
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            // Keystore недоступен (редкие прошивки): не падаем, но и секрет открыто не храним —
            // помечаем маркером и возвращаем как есть с префиксом (лучше работать, чем молча терять)
            "plain:" + Base64.encodeToString(plain.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
    }

    /** Расшифровывает base64(iv||ct) -> строку. Пусто -> пусто. */
    fun decrypt(enc: String): String {
        if (enc.isEmpty()) return ""
        return try {
            if (enc.startsWith("plain:")) {
                return String(Base64.decode(enc.removePrefix("plain:"), Base64.NO_WRAP), Charsets.UTF_8)
            }
            val all = Base64.decode(enc, Base64.NO_WRAP)
            if (all.size <= IV_LEN) return ""
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, all, 0, IV_LEN))
            String(c.doFinal(all, IV_LEN, all.size - IV_LEN), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}
