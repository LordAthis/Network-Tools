// Verzio: v0.1.0 - 2026-09-21
package hu.lordathis.networktools.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A titkosítás állapota a UI-nak. [kid] a kulcs ujjlenyomata (nem titok). */
data class CryptoState(val ready: Boolean, val kid: String?, val busy: Boolean = false)

/**
 * A TITKOSÍTÁS KÜLÖN, ÖNÁLLÓ SZOLGÁLTATÁS az alkalmazáson belül, saját szál-készlettel
 * (2-4 szál a processzormagok számától függően). A feldolgozó motor bármelyik része hívhatja
 * ([encrypt]/[decrypt], suspend), és egyszerre TÖBB hívás is futhat párhuzamosan - a
 * [EnvelopeCodec] szálbiztos, hívásonként új Cipher példányt használ.
 *
 * Kulcs-kezelés:
 *  - a felhasználó által megadott vagy generált kulcs (jelszó-karakterlánc, lásd [KeyPolicy])
 *    az Android Keystore (rendszer-szintű, hardveresen védett) AES-kulcsával TITKOSÍTVA tárolódik;
 *  - induláskor a háttérben visszafejtődik, és kulccsá származtatódik (PBKDF2), a memóriában él;
 *  - a titkosítás maga a rendszer JCA/Conscrypt megvalósításával (hardver-gyorsított AES) fut.
 */
class CryptoService(context: Context, private val onLog: (String) -> Unit) {

    private val prefs = context.applicationContext.getSharedPreferences("networktools_crypto", Context.MODE_PRIVATE)
    private val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
    private val executor = Executors.newFixedThreadPool(threads) { r ->
        Thread(r, "networktools-crypto").apply { isDaemon = true }
    }
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile
    private var codec: EnvelopeCodec? = null

    private val stateFlow = MutableStateFlow(CryptoState(ready = false, kid = null))
    val state: StateFlow<CryptoState> = stateFlow.asStateFlow()

    val isReady: Boolean get() = codec != null

    /** Van-e elmentett kulcs (akkor is, ha még tölt/származtat). */
    val hasStoredKey: Boolean get() = prefs.contains(KEY_VAULT)

    init {
        if (prefs.contains(KEY_VAULT)) {
            stateFlow.value = CryptoState(ready = false, kid = null, busy = true)
            scope.launch { loadStoredKey() }
        }
    }

    /** Új, a szabályoknak megfelelő véletlen kulcs (még NEM lett elmentve). */
    fun generatePassphrase(): String = KeyPolicy.generate()

    /** A megadott kulcs beállítása. Siker esetén a kulcs ujjlenyomatát (kid) adja. */
    suspend fun setPassphrase(passphrase: String): Result<String> = withContext(dispatcher) {
        runCatching {
            val problems = KeyPolicy.validate(passphrase)
            require(problems.isEmpty()) { "A kulcs nem felel meg: " + problems.joinToString("; ") }
            stateFlow.value = stateFlow.value.copy(busy = true)
            val salt = EnvelopeCodec.randomSalt()
            val newCodec = EnvelopeCodec(passphrase, salt)
            val wrapped = KeyVault.wrap(passphrase)
            prefs.edit()
                .putString(KEY_VAULT, wrapped)
                .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .apply()
            codec = newCodec
            stateFlow.value = CryptoState(ready = true, kid = newCodec.kid)
            newCodec.kid
        }.onSuccess { onLog("Titkosítási kulcs beállítva (azonosító: $it).") }
            .onFailure {
                stateFlow.value = CryptoState(ready = codec != null, kid = codec?.kid)
                onLog("Kulcs beállítása sikertelen: ${it.message}")
            }
    }

    /** A kulcs törlése: a titkosítás kikapcsol. */
    fun clearKey() {
        prefs.edit().remove(KEY_VAULT).remove(KEY_SALT).apply()
        codec = null
        stateFlow.value = CryptoState(ready = false, kid = null)
        onLog("Titkosítási kulcs törölve - a titkosítás KIKAPCSOLT.")
    }

    /** A tárolt kulcs visszafejtve (másoláshoz/megjelenítéshez); null, ha nincs vagy nem olvasható. */
    suspend fun revealPassphrase(): String? = withContext(dispatcher) {
        val wrapped = prefs.getString(KEY_VAULT, null) ?: return@withContext null
        runCatching { KeyVault.unwrap(wrapped) }.getOrNull()
    }

    suspend fun encrypt(plain: String): String = withContext(dispatcher) {
        val c = codec ?: error("Nincs titkosítási kulcs beállítva.")
        c.encrypt(plain)
    }

    suspend fun decrypt(token: String): String = withContext(dispatcher) {
        val c = codec ?: error("Nincs titkosítási kulcs beállítva.")
        c.decrypt(token)
    }

    /** Ha a szöveg boríték és van kulcs: visszafejti; egyébként változatlanul adja vissza. */
    suspend fun decryptIfEnvelope(text: String): String {
        if (!EnvelopeCodec.looksLikeEnvelope(text) || codec == null) return text
        return runCatching { decrypt(text) }.getOrElse {
            onLog("Titkosított üzenet visszafejtése sikertelen: ${it.message}")
            text
        }
    }

    fun close() {
        scope.cancel()
        executor.shutdown()
    }

    private fun loadStoredKey() {
        try {
            val wrapped = prefs.getString(KEY_VAULT, null) ?: return
            val salt = Base64.decode(prefs.getString(KEY_SALT, "") ?: "", Base64.NO_WRAP)
            val passphrase = KeyVault.unwrap(wrapped)
            val loaded = EnvelopeCodec(passphrase, salt)
            codec = loaded
            stateFlow.value = CryptoState(ready = true, kid = loaded.kid)
            onLog("Titkosítás BEKAPCSOLVA (kulcs azonosító: ${loaded.kid}, ${threads} párhuzamos szál).")
        } catch (e: Exception) {
            // Pl. eszközcsere/visszaállítás után a Keystore kulcs nem létezik: új kulcsot kell megadni.
            prefs.edit().remove(KEY_VAULT).remove(KEY_SALT).apply()
            codec = null
            stateFlow.value = CryptoState(ready = false, kid = null)
            onLog("A tárolt titkosítási kulcs nem olvasható (${e.message}) - kérlek add meg újra.")
        }
    }

    /** Az Android Keystore-ban tárolt AES-kulcs, amely a jelszó-karakterláncot védi (nyugalmi állapotban). */
    private object KeyVault {
        private const val ALIAS = "networktools_vault_v1"
        private const val PROVIDER = "AndroidKeyStore"

        private fun secretKey(): SecretKey {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return generator.generateKey()
        }

        fun wrap(plain: String): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        }

        fun unwrap(token: String): String {
            val (ivText, ctText) = token.split(":", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP))
            )
            return String(cipher.doFinal(Base64.decode(ctText, Base64.NO_WRAP)), Charsets.UTF_8)
        }
    }

    companion object {
        private const val KEY_VAULT = "vault"
        private const val KEY_SALT = "salt"
    }
}
