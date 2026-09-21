// Verzio: v0.1.0 - 2026-09-21
package hu.lordathis.networktools.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Szöveg-titkosítás AES-256-GCM-mel, jelszó-karakterláncból származtatott kulccsal.
 * Tiszta JVM (nincs Android-függés), szálbiztos: egyszerre tetszőleges számú szálról hívható.
 *
 * Boríték-formátum:
 *     nt1.<só>.<iv>.<titkosított szöveg + GCM címke>      (mindegyik base64url, kitöltés nélkül)
 *
 *  - kulcs: PBKDF2-HMAC-SHA256([passphrase] UTF-8, [só], 100 000 kör) -> 32 bájt
 *  - iv: 12 véletlen bájt üzenetenként (SOHA nem ismétlődik)
 *  - AAD (hitelesített kiegészítő adat): "networktools-v1"
 * A só üzenetenként ugyanaz (a kliens egy kulcs-beállításhoz egyet generál), a fogadó a
 * borítékból olvassa ki, és a származtatott kulcsot gyorsítótárazza.
 */
class EnvelopeCodec(passphrase: String, val salt: ByteArray = randomSalt()) {

    private val passphraseChars = passphrase
    private val keyCache = ConcurrentHashMap<String, SecretKeySpec>()
    private val ownKey: SecretKeySpec = keyFor(salt)

    /** A származtatott kulcs ujjlenyomata (SHA-256 első 4 bájtja, hexa) - azonosításra, nem titok. */
    val kid: String = MessageDigest.getInstance("SHA-256").digest(ownKey.encoded)
        .take(4).joinToString("") { "%02x".format(it) }

    private fun keyFor(saltBytes: ByteArray): SecretKeySpec {
        val id = B64_ENC.encodeToString(saltBytes)
        return keyCache.getOrPut(id) { deriveKey(passphraseChars, saltBytes) }
    }

    fun encrypt(plain: String, random: SecureRandom = SecureRandom()): String {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, ownKey, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD)
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return listOf(PREFIX, B64_ENC.encodeToString(salt), B64_ENC.encodeToString(iv), B64_ENC.encodeToString(ct))
            .joinToString(".")
    }

    /** Visszafejti a borítékot; hibás formátumnál/kulcsnál/módosított adatnál kivételt dob. */
    fun decrypt(token: String): String {
        val parts = token.trim().split(".")
        require(parts.size == 4 && parts[0] == PREFIX) { "nem Network Tool's boríték" }
        val saltBytes = B64_DEC.decode(parts[1])
        val iv = B64_DEC.decode(parts[2])
        val ct = B64_DEC.decode(parts[3])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyFor(saltBytes), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD)
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    companion object {
        const val PREFIX = "nt1"
        const val PBKDF2_ITERATIONS = 100_000
        private const val KEY_BITS = 256
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private val AAD = "networktools-v1".toByteArray(Charsets.UTF_8)
        private val B64_ENC = Base64.getUrlEncoder().withoutPadding()
        private val B64_DEC = Base64.getUrlDecoder()

        fun randomSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

        fun looksLikeEnvelope(text: String): Boolean {
            val t = text.trim()
            return t.startsWith("$PREFIX.") && t.count { it == '.' } == 3
        }

        internal fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
            val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            spec.clearPassword()
            return SecretKeySpec(bytes, "AES")
        }
    }
}
