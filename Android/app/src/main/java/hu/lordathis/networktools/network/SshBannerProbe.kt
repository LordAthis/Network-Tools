// Verzio: v0.5.0 - 2026-09-22
package hu.lordathis.networktools.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * SSH-banner lekérdezése: az SSH-protokoll szerint a szerver a kapcsolódás UTÁN, hitelesítés
 * NÉLKÜL rögtön küld egy szöveges azonosító sort (pl. "SSH-2.0-OpenSSH_9.6"). Ehhez nem kell
 * teljes SSH-implementáció, csak a nyers TCP-kapcsolat első sorának beolvasása.
 */
object SshBannerProbe {
    suspend fun fetchBanner(host: String, port: Int = 22, timeoutMs: Int = 1500): String? = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                val line = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                line?.trim()?.takeIf { it.startsWith("SSH-") }
            }
        } catch (e: Exception) {
            null
        }
    }
}
