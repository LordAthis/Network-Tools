// Verzio: v0.1.0 - 2026-09-27
package hu.lordathis.networktools.speed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.random.Random

/**
 * M2 - Stabilitás / csomagvesztés teszt NYERS UDP-csomagokkal (újraküldés és sorrendezés NÉLKÜL -
 * pontosan az, amit a packetlosstest.com a WebRTC DataChannel `ordered:false, maxRetransmits:0`
 * beállításával kikényszerít).
 *
 * Mivel egy telepített appnak nincs saját "echo" szervere a hálózaton, a visszhangot egy DNS-
 * szerver adja: minden csomag egy apró, egyedi azonosítójú DNS-lekérdezés (UDP/53), amit a
 * router (a legtöbb otthoni router maga is DNS-továbbító) vagy egy nyilvános DNS azonnal
 * megválaszol. A csomagméretet EDNS0 "padding" opcióval (RFC 7830) állítjuk a presetnek
 * megfelelőre (pl. VoIP ~172 bájt). Ha a cél nem kezeli az EDNS-t, automatikusan padding nélkül
 * folytatjuk.
 *
 * Metrikák: csomagvesztés %, RTT min/átlag/max, jitter (RFC 3550: J += (|D| - J) / 16),
 * késve (>1 s) érkező csomagok, és 1 mp-es ablakonkénti veszteség (szakaszos kiesések kimutatására).
 */
class UdpStabilityTester(
    private val emit: (String) -> Unit,
    private val onLive: (StabilityLive) -> Unit,
) {
    private val qname = "example.com"

    /** Rövid előpróba: válaszol-e a cél DNS-lekérdezésre (3 próbából legalább 1). */
    suspend fun probeTarget(target: String, padded: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val addr = InetAddress.getByName(target)
            DatagramSocket().use { socket ->
                socket.soTimeout = 700
                socket.connect(InetSocketAddress(addr, 53))
                repeat(3) { i ->
                    val id = 0x7000 + i
                    val q = buildQuery(id, if (padded) 120 else 0)
                    socket.send(DatagramPacket(q, q.size))
                    val buf = ByteArray(2048)
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(p)
                        if (p.length >= 2 && ((buf[0].toInt() and 0xFF) shl 8 or (buf[1].toInt() and 0xFF)) == id) return@withContext true
                    } catch (e: SocketTimeoutException) {
                        // következő próba
                    }
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    suspend fun run(target: String, preset: StabilityPreset, padded: Boolean): StabilityResult = withContext(Dispatchers.IO) {
        val addr = InetAddress.getByName(target)
        val totalSec = preset.durationSec
        val sendTimes = ConcurrentHashMap<Int, Long>() // seq -> küldési idő (ns)
        val sendSecond = ConcurrentHashMap<Int, Int>()
        val rtts = ArrayList<Double>()
        val windowsSent = IntArray(totalSec + 2)
        val windowsRecv = IntArray(totalSec + 2)
        var received = 0
        var late = 0
        var jitter = 0.0
        var lastTransit: Double? = null
        var lastRtt: Double? = null
        val lock = Any()
        val stop = AtomicBoolean(false)
        val lateThresholdNs = 1_000_000_000L

        val socket = DatagramSocket()
        socket.soTimeout = 200
        socket.connect(InetSocketAddress(addr, 53))

        val receiver = Thread {
            val buf = ByteArray(4096)
            while (!stop.get()) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(p)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    break
                }
                val now = System.nanoTime()
                if (p.length < 2) continue
                val id = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
                val sent = sendTimes.remove(id) ?: continue
                val rttNs = now - sent
                synchronized(lock) {
                    if (rttNs > lateThresholdNs) {
                        late++
                    } else {
                        received++
                        val sec = sendSecond[id] ?: 0
                        if (sec in windowsRecv.indices) windowsRecv[sec]++
                        val rtt = rttNs / 1e6
                        rtts.add(rtt)
                        lastRtt = rtt
                        val prev = lastTransit
                        if (prev != null) jitter += (abs(rtt - prev) - jitter) / 16.0
                        lastTransit = rtt
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "networktools-udp-rx"
            start()
        }

        emit("Cél: $target:53 (UDP), preset: ${preset.label}, " +
            (if (preset.variable) "változó ráta 20-128 csomag/s, változó méret" else "${preset.packetsPerSec} csomag/s, ~${preset.payloadBytes} bájt") +
            ", $totalSec s" + if (!padded) " (EDNS-padding nélkül)" else "")

        var seq = 0
        val startNs = System.nanoTime()
        var nextSendNs = startNs
        var rate = preset.packetsPerSec
        var size = preset.payloadBytes
        var lastReportSec = -1
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val now = System.nanoTime()
                val elapsedNs = now - startNs
                if (elapsedNs >= totalSec * 1_000_000_000L) break
                val sec = (elapsedNs / 1_000_000_000L).toInt()

                if (preset.variable && sec % 2 == 0 && sec != lastReportSec) {
                    rate = Random.nextInt(20, 129)
                    size = Random.nextInt(60, 301)
                }

                if (now >= nextSendNs) {
                    val id = seq and 0xFFFF
                    val q = buildQuery(id, if (padded) size else 0)
                    sendTimes[id] = System.nanoTime()
                    sendSecond[id] = sec
                    try {
                        socket.send(DatagramPacket(q, q.size))
                    } catch (e: Exception) {
                        // küldési hiba (pl. nincs útvonal) = elveszett csomag
                    }
                    synchronized(lock) { if (sec in windowsSent.indices) windowsSent[sec]++ }
                    seq++
                    nextSendNs += 1_000_000_000L / rate
                    if (nextSendNs < now - 200_000_000L) nextSendNs = now // ha nagyon lemaradtunk, ne "pótoljon" burst-tel
                }

                if (sec != lastReportSec) {
                    lastReportSec = sec
                    publish(sec, totalSec, seq, lock, { received }, rtts, { lastRtt }, { jitter }, windowsSent, windowsRecv)
                    if (sec > 0 && sec % 5 == 0) {
                        val snap = synchronized(lock) { received }
                        val sentSoFar = synchronized(lock) { windowsSent.take(sec).sum() }
                        val recvSoFar = synchronized(lock) { windowsRecv.take(sec).sum() }
                        val loss = if (sentSoFar == 0) 0.0 else (sentSoFar - recvSoFar) * 100.0 / sentSoFar
                        emit("${sec}s: elküldve $seq, válasz $snap, veszteség ${"%.2f".format(loss)}%, jitter ${"%.2f".format(jitter)} ms")
                    }
                }
                val waitMs = ((nextSendNs - System.nanoTime()) / 1_000_000L).coerceIn(1L, 50L)
                delay(waitMs)
            }
            // A még úton lévő válaszok bevárása (a "késve érkezett" határig).
            delay(1_100)
        } finally {
            stop.set(true)
            runCatching { socket.close() }
            runCatching { receiver.join(1_000) }
        }

        val sent = seq
        val (recvFinal, lateFinal, rttList, jitterFinal) = synchronized(lock) { Quad(received, late, ArrayList(rtts), jitter) }
        val lost = (sent - recvFinal).coerceAtLeast(0)
        val lossPct = if (sent == 0) 0.0 else lost * 100.0 / sent
        val worstWindow = (0 until totalSec).map { s ->
            StabilityWindow(s, windowsSent[s], windowsRecv[s]).lossPct
        }.maxOrNull() ?: 0.0
        publish(totalSec, totalSec, sent, lock, { recvFinal }, rttList, { rttList.lastOrNull() }, { jitterFinal }, windowsSent, windowsRecv)

        StabilityResult(
            target = target,
            preset = preset,
            sent = sent,
            received = recvFinal,
            late = lateFinal,
            lossPct = lossPct,
            rttMinMs = rttList.minOrNull(),
            rttAvgMs = if (rttList.isEmpty()) null else rttList.average(),
            rttMaxMs = rttList.maxOrNull(),
            jitterMs = if (rttList.size >= 2) jitterFinal else null,
            worstWindowLossPct = worstWindow,
            targetIsLocal = LinkInfo.isPrivateIpv4(addr.hostAddress ?: ""),
        )
    }

    private data class Quad(val a: Int, val b: Int, val c: List<Double>, val d: Double)

    private fun publish(
        sec: Int,
        totalSec: Int,
        sent: Int,
        lock: Any,
        received: () -> Int,
        rtts: List<Double>,
        lastRtt: () -> Double?,
        jitter: () -> Double,
        windowsSent: IntArray,
        windowsRecv: IntArray,
    ) {
        val live = synchronized(lock) {
            val recv = received()
            // Az utolsó, még le nem zárt másodperc csomagjai még úton lehetnek - azokat nem számoljuk veszteségnek.
            val closed = (sec - 1).coerceAtLeast(0)
            val sentClosed = windowsSent.take(closed).sum()
            val recvClosed = windowsRecv.take(closed).sum()
            val loss = if (sentClosed == 0) 0.0 else ((sentClosed - recvClosed).coerceAtLeast(0)) * 100.0 / sentClosed
            StabilityLive(
                elapsedSec = sec,
                totalSec = totalSec,
                sent = sent,
                received = recv,
                lossPct = loss,
                lastRttMs = lastRtt(),
                avgRttMs = if (rtts.isEmpty()) null else rtts.average(),
                jitterMs = if (rtts.size >= 2) jitter() else null,
                windows = (0 until closed).map { StabilityWindow(it, windowsSent[it], windowsRecv[it]) }.takeLast(60),
            )
        }
        onLive(live)
    }

    /**
     * Egy DNS "A" lekérdezés [id] tranzakció-azonosítóval. Ha [targetSize] > 0, EDNS0 OPT rekord
     * padding-opcióval egészül ki, hogy a teljes UDP-tartalom ~[targetSize] bájt legyen.
     */
    private fun buildQuery(id: Int, targetSize: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) {
            out.write((v shr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        val withOpt = targetSize > 0
        u16(id)
        u16(0x0100) // RD
        u16(1)      // QDCOUNT
        u16(0)
        u16(0)
        u16(if (withOpt) 1 else 0) // ARCOUNT
        for (label in qname.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        u16(1) // A
        u16(1) // IN
        if (withOpt) {
            val base = out.size() + 11 + 4
            val pad = (targetSize - base).coerceAtLeast(0)
            out.write(0)      // root név
            u16(41)           // OPT
            u16(1232)         // UDP payload méret
            out.write(0); out.write(0) // ext. RCODE, verzió
            u16(0)            // flags
            u16(4 + pad)      // RDLEN
            u16(12)           // Padding opció
            u16(pad)
            repeat(pad) { out.write(0) }
        }
        return out.toByteArray()
    }
}
