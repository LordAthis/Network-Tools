// Verzio: v0.5.0 - 2026-09-22
package hu.lordathis.networktools.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.measureTimeMillis

/** WAN (internet) elérhetőségi teszt eredménye - a MAX v2 §7/B megfelelője. */
data class WanTestResult(val target: String, val reachable: Boolean, val attempts: Int, val succeeded: Int) {
    val lossPercent: Int get() = if (attempts == 0) 100 else ((attempts - succeeded) * 100) / attempts
}

data class DnsTimingResult(val hostname: String, val resolvedIp: String?, val millis: Long?)

object GatewayTest {

    /** Néhány közismert, stabil cél (443-as porttal, mert szinte mindenhol nyitva van). */
    private val WAN_TARGETS = listOf("1.1.1.1" to 443, "8.8.8.8" to 443)

    suspend fun testWan(attemptsPerTarget: Int = 5, timeoutMs: Int = 800): List<WanTestResult> =
        withContext(Dispatchers.IO) {
            WAN_TARGETS.map { (host, port) ->
                var ok = 0
                repeat(attemptsPerTarget) {
                    if (canConnect(host, port, timeoutMs)) ok++
                }
                WanTestResult(host, ok > 0, attemptsPerTarget, ok)
            }
        }

    suspend fun dnsResolveTiming(hostname: String = "google.com"): DnsTimingResult = withContext(Dispatchers.IO) {
        var ip: String? = null
        val elapsed = try {
            measureTimeMillis { ip = java.net.InetAddress.getByName(hostname).hostAddress }
        } catch (e: Exception) {
            null
        }
        DnsTimingResult(hostname, ip, elapsed)
    }

    private fun canConnect(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    } catch (e: Exception) {
        false
    }
}
