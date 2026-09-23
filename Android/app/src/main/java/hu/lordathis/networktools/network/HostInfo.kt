// Verzio: v0.5.0 - 2026-09-22
package hu.lordathis.networktools.network

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.UnknownHostException

/** Hostname-feloldás (fordított DNS) - a MAX v2 §6/A megfelelője. */
object HostInfo {
    suspend fun reverseLookup(ip: String): String? = withContext(Dispatchers.IO) {
        try {
            val addr = InetAddress.getByName(ip)
            val name = addr.canonicalHostName
            // getCanonicalHostName sikertelen feloldásnál magát az IP-t adja vissza - ez nem "eredmény".
            name.takeIf { it != ip }
        } catch (e: UnknownHostException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /** A saját eszköz alap infói (a reszletes_halozati_jelentes.bat `systeminfo` kivonatának megfelelője). */
    fun deviceSummary(): Map<String, String> = mapOf(
        "Gyártó" to Build.MANUFACTURER,
        "Modell" to Build.MODEL,
        "Android verzió" to Build.VERSION.RELEASE,
        "API szint" to Build.VERSION.SDK_INT.toString(),
        "Build" to Build.DISPLAY,
    )
}
