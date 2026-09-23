// Verzio: v0.5.0 - 2026-09-22
package hu.lordathis.networktools.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Elérhetőség-vizsgálat TCP-connect próbával, ICMP helyett.
 *
 * Nem-root Android appból nincs megbízható, hordozható ICMP-ping API (a nyers ICMP-socket
 * root/CAP_NET_RAW-ot igényel; a rendszer `ping` binárisának `exec`-elése működhet, de eszköz-
 * és ROM-függő, és a szöveges kimenet nyelvfüggő parse-olást igényelne). Emiatt itt egy rövid
 * TCP-kapcsolódási kísérletet használunk néhány gyakori porton: ha BÁRMELYIK elfogadja a
 * kapcsolatot (vagy kifejezetten "connection refused"-dal utasítja el - ez is azt jelenti, hogy
 * VALAKI figyel az IP-n), a host élőnek számít.
 */
object PingTools {

    /** A próbaportok: gyors találat esély + minél kevesebb hamis-negatív. */
    private val PROBE_PORTS = intArrayOf(80, 443, 22, 445, 139, 8080, 7)

    /**
     * Egyetlen host él-e. [timeoutMs] portonkénti timeout; a próba az első sikeres/elutasított
     * kapcsolódásnál megáll.
     */
    suspend fun isAlive(host: String, timeoutMs: Int = 300): Boolean = withContext(Dispatchers.IO) {
        for (port in PROBE_PORTS) {
            if (tryConnect(host, port, timeoutMs)) return@withContext true
        }
        false
    }

    /** Egy adott portra kapcsolódás sikere - true, ha elfogadta VAGY explicit elutasította (mindkettő "van ott valaki"). */
    private fun tryConnect(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            true
        } catch (e: java.net.ConnectException) {
            // "Connection refused": a cél gép válaszolt (nincs ott szolgáltatás ezen a porton, de a host ÉL).
            e.message?.contains("refused", ignoreCase = true) == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Sok host párhuzamos vizsgálata, korlátozott egyidejűséggel (throttle), hogy ne terhelje túl
     * se a telefont, se a hálózatot. [onHostChecked] minden host után hívódik (élő visszajelzéshez).
     */
    suspend fun sweep(
        hosts: List<String>,
        concurrency: Int = 32,
        timeoutMs: Int = 300,
        onHostChecked: (host: String, alive: Boolean, done: Int, total: Int) -> Unit = { _, _, _, _ -> },
    ): List<String> = coroutineScope {
        val semaphore = Semaphore(concurrency)
        val doneCount = AtomicInteger(0)
        hosts.map { host ->
            async(Dispatchers.IO) {
                val alive = semaphore.withPermit { isAlive(host, timeoutMs) }
                onHostChecked(host, alive, doneCount.incrementAndGet(), hosts.size)
                host to alive
            }
        }.awaitAll().filter { it.second }.map { it.first }
    }
}
