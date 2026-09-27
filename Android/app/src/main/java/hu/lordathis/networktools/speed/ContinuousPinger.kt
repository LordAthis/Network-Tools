// Verzio: v0.1.0 - 2026-09-27
package hu.lordathis.networktools.speed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.InetAddress
import kotlin.math.abs

/**
 * M3 - Haladó kábelteszt: végtelenített ping (alapból 500 ms-onként), amíg a felhasználó le nem
 * állítja (a hívó job megszakítása = STOP). Minden mintát a [onPoint] kap meg (élő grafikon),
 * kiesésnél az [onLoss] hívódik (hangjelzés).
 */
class ContinuousPinger(
    private val emit: (String) -> Unit,
    private val onPoint: (PingPoint, PingStats) -> Unit,
    private val onLoss: () -> Unit,
) {
    suspend fun run(target: String, intervalMs: Long, method: IcmpPing.Method): PingStats = withContext(Dispatchers.IO) {
        var tcpPort: Int? = null
        if (method == IcmpPing.Method.TCP) {
            val addr = InetAddress.getByName(target)
            tcpPort = listOf(53, 80, 443, 22, 8080).firstOrNull { BandwidthTester.tcpRtt(addr, it, 800) != null }
            if (tcpPort == null) {
                emit("A cél egyik próbált TCP-portra (53/80/443/22/8080) sem válaszol - a TCP-alapú kábelteszt nem futtatható.")
                return@withContext PingStats()
            }
            emit("TCP-alapú mérés a(z) $tcpPort-es porton (ICMP nem elérhető ezen a telefonon).")
        }
        val addr = InetAddress.getByName(target)

        var stats = PingStats()
        var sum = 0.0
        var okCount = 0
        var lastRtt: Double? = null
        var jitter = 0.0
        var seq = 0
        var lastSummaryMs = System.currentTimeMillis()
        while (currentCoroutineContext().isActive) {
            val t0 = System.currentTimeMillis()
            val rtt = if (method == IcmpPing.Method.ICMP) {
                IcmpPing.ping(target, timeoutSec = 1)
            } else {
                BandwidthTester.tcpRtt(addr, tcpPort ?: 80, 1_000)
            }
            seq++
            if (rtt != null) {
                okCount++
                sum += rtt
                val prev = lastRtt
                if (prev != null) jitter += (abs(rtt - prev) - jitter) / 16.0
                lastRtt = rtt
                stats = stats.copy(
                    sent = stats.sent + 1,
                    minMs = minOf(stats.minMs ?: rtt, rtt),
                    maxMs = maxOf(stats.maxMs ?: rtt, rtt),
                    avgMs = sum / okCount,
                    jitterMs = if (okCount >= 2) jitter else null,
                    currentLossStreak = 0,
                )
            } else {
                val streak = stats.currentLossStreak + 1
                stats = stats.copy(
                    sent = stats.sent + 1,
                    lost = stats.lost + 1,
                    currentLossStreak = streak,
                    maxLossStreak = maxOf(stats.maxLossStreak, streak),
                )
                emit("#$seq: NINCS VÁLASZ (időtúllépés)" + if (streak > 1) " - $streak egymás után" else "")
                onLoss()
            }
            onPoint(PingPoint(seq, System.currentTimeMillis(), rtt), stats)

            if (System.currentTimeMillis() - lastSummaryMs >= 10_000) {
                lastSummaryMs = System.currentTimeMillis()
                emit(
                    "Összesítő: ${stats.sent} ping, ${stats.lost} kiesés (${"%.1f".format(stats.lossPct)}%), " +
                        "min/átl/max: ${BandwidthTester.fmt(stats.minMs)}/${BandwidthTester.fmt(stats.avgMs)}/${BandwidthTester.fmt(stats.maxMs)} ms"
                )
            }
            val wait = intervalMs - (System.currentTimeMillis() - t0)
            if (wait > 0) delay(wait)
        }
        stats
    }
}
