package com.enamind.ai.export

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * ZipCrypto — подпись экспортных ZIP (ECDSA P-256, Android Keystore).
 *
 * Зачем: импорт скилл-ZIP устанавливает Python-код в приложение (это функция
 * обновления ядра). Раньше ZIP не имел подписи: подменённый архив молча
 * исполнялся. Теперь каждый экспорт подписывается ключом устройства, а при
 * импорте подпись проверяется.
 *
 * Модель доверия (честная, без центрального CA):
 * - «свои» архивы (этот же телефон) — подпись устройства, импорт проходит сразу;
 * - чужие подписанные архивы (переезд на другой телефон) — показываем отпечаток
 *   ключа, пользователь подтверждает осознанно;
 * - архивы без подписи (сторонние или старый формат) — явное
 *   предупреждение + подтверждение; от целостности подписи они не защищены.
 *
 * Ключ не покидает Android Keystore; в ZIP попадает только сертификат (публичный).
 */
object ZipCrypto {

    private const val ALIAS = "enamind_zip_key"
    const val SIGNATURE_ENTRY = "ena-mind/signature.json"
    const val ALG = "SHA256withECDSA"

    // ---------------- ключ устройства ----------------

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** Ключевая пара устройства (создаётся при первом экспорте). */
    private fun ensureKeyPair(): Boolean = try {
        val ks = keyStore()
        if (!ks.containsAlias(ALIAS)) {
            val kpg = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
            kpg.initialize(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_SIGN or
                        android.security.keystore.KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
                    .build()
            )
            kpg.generateKeyPair()
        }
        true
    } catch (_: Exception) {
        false
    }

    /** Сертификат ключа устройства (Base64 DER) — публикуется в ZIP. */
    private fun deviceCertificateB64(): String? {
        return try {
            ensureKeyPair()
            val cert = keyStore().getCertificate(ALIAS) ?: return null
            Base64.getEncoder().encodeToString(cert.encoded)
        } catch (_: Exception) {
            null
        }
    }

    // ---------------- подпись ----------------

    /**
     * Подпись манифеста. manifest — точные байты JSON-манифеста (файл→SHA-256);
     * подпись считается по этим байтам, манифест кладётся в ZIP рядом с подписью,
     * поэтому порядок ключей JSON не влияет на проверку.
     */
    fun sign(manifest: ByteArray): JSONObject? {
        return try {
            if (!ensureKeyPair()) return null
            val entry = keyStore().getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
                ?: return null
            val sig = Signature.getInstance(ALG).apply {
                initSign(entry.privateKey)
                update(manifest)
            }.sign()
            val certB64 = deviceCertificateB64() ?: return null
            JSONObject()
                .put("alg", ALG)
                .put("sig", Base64.getEncoder().encodeToString(sig))
                .put("cert", certB64)
                .put("fingerprint", fingerprintOf(certB64))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Проверка подписи из signature.json. Возвращает:
     * {"ok":true, "trusted":bool, "fingerprint":hex} — подпись математически верна;
     * {"ok":false, "error":...} — подпись не сходится или формат повреждён.
     * trusted=true — сертификат совпадает с ключом ЭТОГО устройства.
     */
    fun verify(signatureJson: org.json.JSONObject): org.json.JSONObject {
        return try {
            val sig = Base64.getDecoder().decode(signatureJson.optString("sig"))
            val certB64 = signatureJson.optString("cert")
            val manifest = Base64.getDecoder().decode(signatureJson.optString("manifest_b64"))
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(certB64)))
                as X509Certificate
            cert.checkValidity()
            val ok = Signature.getInstance(ALG).apply {
                initVerify(cert.publicKey)
                update(manifest)
            }.verify(sig)
            if (!ok) {
                return JSONObject().put("ok", false)
                    .put("error", "подпись не сходится — архив изменён или повреждён")
            }
            val fp = fingerprintOf(certB64)
            val own = deviceCertificateB64()?.let { fingerprintOf(it) == fp } ?: false
            JSONObject().put("ok", true).put("trusted", own).put("fingerprint", fp)
        } catch (e: Exception) {
            JSONObject().put("ok", false)
                .put("error", "повреждён блок подписи: ${e.message}")
        }
    }

    /** Короткий отпечаток сертификата: SHA-256(DER), первые 16 hex. */
    fun fingerprintOf(certB64: String): String = try {
        val der = Base64.getDecoder().decode(certB64)
        sha256(der).take(16)
    } catch (_: Exception) {
        "????"
    }

    fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
}
