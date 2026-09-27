// Verzio: v0.1.0 - 2026-09-27
package hu.lordathis.networktools.speed

import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Egyetlen ICMP-ping a rendszer `ping` programjával (a legtöbb Android-eszközön root nélkül is fut,
 * mert a kernel "ping socket"-et ad a normál appoknak). Ha nem elérhető, a hívó TCP-kapcsolódásos
 * RTT-mérésre vált ([BandwidthTester.tcpRtt]).
 *
 * A kimenetből csak a "time=12.3 ms" részt olvassuk - ez az iputils/toybox ping-nél nyelvfüggetlen.
 */
object IcmpPing {

    enum class Method(val label: String) { ICMP("ICMP (rendszer ping)"), TCP("TCP-kapcsolódás (ICMP nem elérhető)") }

    private val TIME_RE = Regex("""time[=<]\s*([0-9]+(?:[.,][0-9]+)?)\s*ms""")
    private val PING_PATHS = listOf("/system/bin/ping", "ping")

    @Volatile
    private var workingPath: String? = null

    @Volatile
    private var icmpAvailable: Boolean? = null

    /**
     * Egy ping; visszatérés: RTT ms-ban, vagy null (nincs válasz / időtúllépés).
     * [payloadBytes]: a ping `-s` paramétere (az ICMP-adat mérete).
     */
    fun ping(host: String, timeoutSec: Int = 1, payloadBytes: Int? = null): Double? {
        val path = workingPath ?: PING_PATHS.first()
        val cmd = ArrayList<String>()
        cmd += path
        cmd += listOf("-c", "1", "-W", timeoutSec.coerceAtLeast(1).toString())
        if (payloadBytes != null) cmd += listOf("-s", payloadBytes.toString())
        cmd += host
        var process: Process? = null
        return try {
            process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val finished = process.waitFor((timeoutSec + 2).toLong(), TimeUnit.SECONDS)
            if (!finished) return null
            val out = process.inputStream.bufferedReader().readText()
            TIME_RE.find(out)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        } catch (e: Exception) {
            null
        } finally {
            process?.destroy()
        }
    }

    /**
     * Kideríti (és megjegyzi), hogy a rendszer-ping használható-e. Egy lokális cím (loopback), majd a
     * megadott cél pingelésével: ha bármelyik "time=" választ ad, ICMP elérhető.
     */
    fun detect(sampleHost: String?): Method {
        icmpAvailable?.let { return if (it) Method.ICMP else Method.TCP }
        for (p in PING_PATHS) {
            workingPath = p
            val ok = ping("127.0.0.1") != null || (sampleHost != null && ping(sampleHost) != null)
            if (ok) {
                icmpAvailable = true
                return Method.ICMP
            }
        }
        workingPath = null
        icmpAvailable = false
        return Method.TCP
    }

    /** TCP-alapú tartalék: az első válaszoló port kapcsolódási ideje (elfogadás vagy elutasítás egyaránt). */
    fun tcpPing(host: String, ports: List<Int> = listOf(53, 80, 443, 22), timeoutMs: Int = 1_000): Double? {
        val addr = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            return null
        }
        for (port in ports) {
            val rtt = BandwidthTester.tcpRtt(addr, port, timeoutMs)
            if (rtt != null) return rtt
        }
        return null
    }
}
