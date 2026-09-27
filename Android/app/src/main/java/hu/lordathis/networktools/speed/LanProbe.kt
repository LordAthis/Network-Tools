// Verzio: v0.1.0 - 2026-09-27
package hu.lordathis.networktools.speed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * LAN-mérés (külön kapcsolóval): a hálózaton belüli eszközökhöz képest.
 *
 * Minden célhoz:
 *  - [SMALL_SAMPLES] kis ping (RTT, veszteség) - a MINIMUM RTT a sorban állástól mentes "alap" késés;
 *  - [LARGE_SAMPLES] nagy (1400 bájtos) ping. A kettő minimumának különbsége a csomagok
 *    szerializációs ideje oda-vissza az útvonalon -> ebből becsülhető a leglassabb szakasz sebessége:
 *      Mbps ≈ (2 * (1400 - 16) * 8 bit) / (különbség µs-ban)
 *    Egy 100 Mbps-os szakasz ~0,22 ms-ot, egy gigabites ~0,02 ms-ot ad hozzá. Ezért ez csak ICMP-vel
 *    és stabil kapcsolaton értelmes: KÍSÉRLETI, tájékoztató érték - a lényeg az eszközök
 *    EGYMÁSHOZ viszonyított eltérése (melyik lóg ki).
 */
class LanProbe(
    private val emit: (String) -> Unit,
    private val onProgress: (done: Int, total: Int, partial: List<LanMeasurement>) -> Unit,
) {
    suspend fun run(targets: List<LanTarget>, method: IcmpPing.Method): List<LanMeasurement> = withContext(Dispatchers.IO) {
        val results = ArrayList<LanMeasurement>()
        emit("LAN-mérés: ${targets.size} cél, módszer: ${method.label}")
        if (method == IcmpPing.Method.TCP) {
            emit("Figyelem: ICMP nélkül csak RTT/veszteség mérhető, a nagy-csomagos szakasz-becslés nem.")
        }
        targets.forEachIndexed { index, t ->
            currentCoroutineContext().ensureActive()
            val small = ArrayList<Double>()
            val large = ArrayList<Double>()
            var sent = 0
            var lost = 0
            repeat(SMALL_SAMPLES) {
                val rtt = if (method == IcmpPing.Method.ICMP) IcmpPing.ping(t.ip, 1, 16) else IcmpPing.tcpPing(t.ip, timeoutMs = 700)
                sent++
                if (rtt == null) lost++ else small.add(rtt)
                delay(60)
            }
            if (method == IcmpPing.Method.ICMP && small.isNotEmpty()) {
                repeat(LARGE_SAMPLES) {
                    IcmpPing.ping(t.ip, 1, LARGE_PAYLOAD)?.let { large.add(it) }
                    delay(60)
                }
            }
            val sMin = small.minOrNull()
            val lMin = large.minOrNull()
            val estimate = if (sMin != null && lMin != null && lMin > sMin) {
                val bits = 2.0 * (LARGE_PAYLOAD - 16) * 8
                (bits / ((lMin - sMin) * 1000.0)).takeIf { it in 1.0..20_000.0 } // Mbps; a szélsőséges értékek zajnak számítanak
            } else {
                null
            }
            val m = LanMeasurement(
                ip = t.ip,
                label = t.label,
                role = t.role,
                method = method.label,
                sent = sent,
                lost = lost,
                rttSmallMinMs = sMin,
                rttSmallAvgMs = if (small.isEmpty()) null else small.average(),
                rttLargeMinMs = lMin,
                pathEstimateMbps = estimate,
                catalog = t.catalog,
            )
            results += m
            emit(
                "${t.ip} (${t.label}): RTT min ${BandwidthTester.fmt(sMin)} ms, veszteség $lost/$sent" +
                    (lMin?.let { ", nagy csomag min ${BandwidthTester.fmt(it)} ms" } ?: "") +
                    (estimate?.let { ", becsült útvonal ~${BandwidthTester.fmt(it)} Mbps" } ?: "") +
                    (t.catalog?.let { " | lista: ${it.vendor} ${it.model}" } ?: "")
            )
            onProgress(index + 1, targets.size, ArrayList(results))
        }
        results
    }

    companion object {
        const val SMALL_SAMPLES = 10
        const val LARGE_SAMPLES = 10
        const val LARGE_PAYLOAD = 1400
        const val MAX_TARGETS = 40
    }
}
