// Verzio: v0.5.0 - 2026-09-22
package hu.lordathis.networktools.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetSocketAddress
import java.net.Socket

/** Egy nyitott port egy hosztnál. */
data class OpenPort(val host: String, val port: Int)

/** Párhuzamos TCP-connect port-scan - [PortScanTarget.AllPorts] esetén magas [concurrency] ajánlott. */
object PortScanner {

    suspend fun scanHost(
        host: String,
        target: PortScanTarget,
        concurrency: Int = 64,
        timeoutMs: Int = 250,
        onProgress: (checked: Int, total: Int) -> Unit = { _, _ -> },
    ): List<OpenPort> = coroutineScope {
        val ports = target.resolve()
        val semaphore = Semaphore(concurrency)
        val checked = java.util.concurrent.atomic.AtomicInteger(0)
        ports.map { port ->
            async(Dispatchers.IO) {
                val open = semaphore.withPermit { isOpen(host, port, timeoutMs) }
                onProgress(checked.incrementAndGet(), ports.size)
                if (open) OpenPort(host, port) else null
            }
        }.awaitAll().filterNotNull()
    }

    private fun isOpen(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    } catch (e: Exception) {
        false
    }
}
