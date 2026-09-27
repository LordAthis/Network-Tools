// Verzio: v0.1.0 - 2026-09-27
package hu.lordathis.networktools.speed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * M1 - Sávszélesség-teszt (az Ookla/Speedtest logikája szerint): több párhuzamos TCP-kapcsolaton
 * letöltés, majd feltöltés, fix ideig. Az első ~2 mp (TCP slow-start, "felfutás") NEM számít bele az
 * eredménybe. Közben folyamatosan méri a TERHELÉS ALATTI késleltetést is (bufferbloat).
 *
 * Szerverek:
 *  - Cloudflare (speed.cloudflare.com/__down, /__up) - nyilvános, kulcs nélküli teszt-végpont;
 *  - egyedi URL (csak letöltés - egy nagy fájl közvetlen címe);
 *  - LAN SpeedServer: a repó win/SpeedServer.ps1 scriptje egy PC-n - ugyanazokat a végpontokat adja,
 *    sima HTTP-n (itt nyers sockettel beszélünk vele, így az Android cleartext-tiltása nem érinti).
 */
class BandwidthTester(
    private val emit: (String) -> Unit,
    private val onProgress: (phase: String, liveMbps: Double?, progress: Float) -> Unit,
) {
    private data class PhaseResult(val mbps: Double?, val peakMbps: Double?, val bytes: Long, val loadedLatencies: List<Double>)

    private interface Transport {
        val label: String
        val latencyHost: String
        val latencyPort: Int
        val supportsUpload: Boolean
        val downChunk: Long
        val upChunk: Long
        fun download(bytes: Long, counter: AtomicLong, stop: AtomicBoolean, register: (Closeable) -> Unit)
        fun upload(bytes: Long, counter: AtomicLong, stop: AtomicBoolean, register: (Closeable) -> Unit)
    }

    suspend fun run(config: BandwidthConfig): BandwidthResult = withContext(Dispatchers.IO) {
        val transport = buildTransport(config)
        val connections = config.connections.coerceIn(1, 16)
        val duration = config.durationSec.coerceIn(5, 60)
        emit("Szerver: ${transport.label}")
        emit("Párhuzamos kapcsolatok: $connections, fázisonként $duration s (az első ~2 s felfutás nem számít bele)")

        onProgress("Késleltetés (terheletlen)", null, 0.02f)
        val address = try {
            InetAddress.getByName(transport.latencyHost)
        } catch (e: Exception) {
            throw IOException("A szerver neve nem oldható fel: ${transport.latencyHost}")
        }
        val idle = ArrayList<Double>()
        repeat(6) {
            tcpRtt(address, transport.latencyPort)?.let { idle.add(it) }
            delay(120)
        }
        val idleMedian = median(idle)
        emit("Terheletlen késleltetés (TCP-kapcsolódás, medián): ${fmt(idleMedian)} ms (${idle.size}/6 sikeres)")

        val uploadWanted = config.upload && transport.supportsUpload
        val downWeight = if (uploadWanted) 0.48f else 0.95f

        emit("Letöltés indul...")
        val down = runPhase("Letöltés", upload = false, transport, address, connections, duration, 0.05f, downWeight)
        emit("Letöltés: ${fmt(down.mbps)} Mbps (csúcs/plafon: ${fmt(down.peakMbps)} Mbps, ${down.bytes / 1_000_000} MB)")

        var up: PhaseResult? = null
        if (uploadWanted) {
            emit("Feltöltés indul...")
            up = runPhase("Feltöltés", upload = true, transport, address, connections, duration, 0.05f + downWeight, 0.47f)
            emit("Feltöltés: ${fmt(up.mbps)} Mbps (csúcs/plafon: ${fmt(up.peakMbps)} Mbps, ${up.bytes / 1_000_000} MB)")
        } else if (config.upload && !transport.supportsUpload) {
            emit("Feltöltés kihagyva: ez a szerver-típus csak letöltést támogat.")
        }

        val loaded = median(down.loadedLatencies + (up?.loadedLatencies ?: emptyList()))
        emit("Terhelés alatti késleltetés (medián): ${fmt(loaded)} ms")
        onProgress("Kész", null, 1f)

        BandwidthResult(
            serverLabel = transport.label,
            downloadMbps = down.mbps,
            downloadPeakMbps = down.peakMbps,
            uploadMbps = up?.mbps,
            uploadPeakMbps = up?.peakMbps,
            idleLatencyMs = idleMedian,
            loadedLatencyMs = loaded,
            bytesDown = down.bytes,
            bytesUp = up?.bytes ?: 0L,
            connections = connections,
        )
    }

    private suspend fun runPhase(
        name: String,
        upload: Boolean,
        transport: Transport,
        address: InetAddress,
        connections: Int,
        durationSec: Int,
        progressStart: Float,
        progressSpan: Float,
    ): PhaseResult = coroutineScope {
        val counter = AtomicLong(0)
        val stop = AtomicBoolean(false)
        val closeables = ConcurrentLinkedQueue<Closeable>()
        val errors = AtomicInteger(0)
        var lastError: String? = null

        val workers = (1..connections).map {
            launch(Dispatchers.IO) {
                while (!stop.get() && isActive) {
                    try {
                        if (upload) {
                            transport.upload(transport.upChunk, counter, stop) { c -> closeables.add(c) }
                        } else {
                            transport.download(transport.downChunk, counter, stop) { c -> closeables.add(c) }
                        }
                    } catch (e: Exception) {
                        if (stop.get()) break
                        lastError = e.message ?: e.javaClass.simpleName
                        if (errors.incrementAndGet() > connections * 3) break
                        delay(300)
                    }
                }
            }
        }

        val loadedLat = ArrayList<Double>()
        val latencyJob = launch(Dispatchers.IO) {
            delay(1_000)
            while (!stop.get() && isActive) {
                tcpRtt(address, transport.latencyPort)?.let { synchronized(loadedLat) { loadedLat.add(it) } }
                delay(700)
            }
        }

        val startNs = System.nanoTime()
        val totalNs = durationSec * 1_000_000_000L
        val warmupNs = minOf(2_000_000_000L, totalNs / 4)
        val history = ArrayDeque<Pair<Long, Long>>() // (időpont ns, bájtok)
        val samples = ArrayList<Double>()
        var warmBytes = -1L
        var warmNs = 0L
        while (true) {
            delay(250)
            val now = System.nanoTime()
            val bytes = counter.get()
            history.addLast(now to bytes)
            while (history.isNotEmpty() && now - history.first().first > 1_000_000_000L) history.removeFirst()
            val live = if (history.size >= 2) {
                val (t0, b0) = history.first()
                val dt = (now - t0) / 1e9
                if (dt > 0.2) (bytes - b0) * 8 / dt / 1e6 else null
            } else {
                null
            }
            val elapsed = now - startNs
            if (elapsed >= warmupNs) {
                if (warmBytes < 0) {
                    warmBytes = bytes
                    warmNs = now
                } else if (live != null) {
                    samples.add(live)
                }
            }
            onProgress(name, live, progressStart + progressSpan * (elapsed.toFloat() / totalNs).coerceIn(0f, 1f))
            if (elapsed >= totalNs) break
            if (workers.all { it.isCompleted }) break
        }
        val endNs = System.nanoTime()
        val endBytes = counter.get()
        stop.set(true)
        closeables.forEach { runCatching { it.close() } }
        withTimeoutOrNull(6_000) { (workers + latencyJob).joinAll() }
        latencyJob.cancel()

        if (endBytes == 0L) {
            emit("$name: nem érkezett/ment adat" + (lastError?.let { " - hiba: $it" } ?: "") + ".")
        } else if (errors.get() > 0) {
            emit("$name: ${errors.get()} kapcsolat-hiba közben (utolsó: ${lastError ?: "?"}) - az eredmény ettől még érvényes lehet.")
        }

        val mbps = if (warmBytes >= 0 && endNs > warmNs && endBytes > warmBytes) {
            (endBytes - warmBytes) * 8 / ((endNs - warmNs) / 1e9) / 1e6
        } else if (endBytes > 0) {
            endBytes * 8 / ((endNs - startNs) / 1e9) / 1e6
        } else {
            null
        }
        val peak = percentile(samples, 0.9)
        PhaseResult(mbps, peak, endBytes, synchronized(loadedLat) { ArrayList(loadedLat) })
    }

    // ------------------------------------------------------------------------------ Transport-ok

    private fun buildTransport(config: BandwidthConfig): Transport = when (config.server) {
        BandwidthServer.CLOUDFLARE -> HttpsTransport(
            label = "Cloudflare (speed.cloudflare.com)",
            downUrl = { bytes -> "https://speed.cloudflare.com/__down?bytes=$bytes" },
            upUrl = "https://speed.cloudflare.com/__up",
            host = "speed.cloudflare.com",
            port = 443,
            downChunk = 25_000_000L,
            upChunk = 8_000_000L,
        )
        BandwidthServer.CUSTOM -> {
            val raw = config.customUrl.trim()
            require(raw.startsWith("http://") || raw.startsWith("https://")) { "Adj meg egy http(s):// kezdetű letöltési címet." }
            val url = URL(raw)
            if (url.protocol == "http") {
                RawHttpTransport(
                    label = "Egyedi URL ($raw)",
                    host = url.host,
                    port = if (url.port > 0) url.port else 80,
                    downPath = { _ -> url.file.ifEmpty { "/" } },
                    upPath = null,
                    downChunk = Long.MAX_VALUE,
                    upChunk = 0L,
                )
            } else {
                HttpsTransport(
                    label = "Egyedi URL ($raw)",
                    downUrl = { _ -> raw },
                    upUrl = null,
                    host = url.host,
                    port = if (url.port > 0) url.port else 443,
                    downChunk = Long.MAX_VALUE,
                    upChunk = 0L,
                )
            }
        }
        BandwidthServer.LAN_SERVER -> {
            val (host, port) = parseHostPort(config.lanServer, 8765)
            RawHttpTransport(
                label = "LAN SpeedServer ($host:$port)",
                host = host,
                port = port,
                downPath = { bytes -> "/__down?bytes=$bytes" },
                upPath = "/__up",
                downChunk = 200_000_000L,
                upChunk = 100_000_000L,
            )
        }
    }

    private class HttpsTransport(
        override val label: String,
        private val downUrl: (Long) -> String,
        private val upUrl: String?,
        host: String,
        port: Int,
        override val downChunk: Long,
        override val upChunk: Long,
    ) : Transport {
        override val latencyHost = host
        override val latencyPort = port
        override val supportsUpload = upUrl != null

        override fun download(bytes: Long, counter: AtomicLong, stop: AtomicBoolean, register: (Closeable) -> Unit) {
            val conn = URL(downUrl(bytes)).openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.useCaches = false
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("Cache-Control", "no-cache")
            register(Closeable { conn.disconnect() })
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                conn.inputStream.use { drain(it, counter, stop) }
            } finally {
                conn.disconnect()
            }
        }

        override fun upload(bytes: Long, counter: AtomicLong, stop: AtomicBoolean, register: (Closeable) -> Unit) {
            val target = upUrl ?: return
            val conn = URL(target).openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            conn.useCaches = false
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setFixedLengthStreamingMode(bytes)
            register(Closeable { conn.disconnect() })
            try {
                val out = conn.outputStream
                val complete = pump(out::write, bytes, counter, stop)
                if (complete) {
                    out.close()
                    conn.responseCode // a válasz kiolvasása (a szerver visszaigazolja a feltöltést)
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private class RawHttpTransport(
        override val label: String,
        private val host: String,
        private val port: Int,
        private val downPath: (Long) -> String,
        private val upPath: String?,
        override val downChunk: Long,
        override val upChunk: Long,
    ) : Transport {
        override val latencyHost = host
        override val latencyPort = port
        override val supportsUpload = upPath != null

        private fun open(register: (Closeable) -> Unit): Socket {
            val s = Socket()
            register(s)
            s.connect(InetSocketAddress(host, port), 4_000)
            s.soTimeout = 6_000
            s.tcpNoDelay = true
            return s
        }

        override fun download(bytes: Long, counter: AtomicLong, stop: AtomicBoolean, register: (Closeable) -> Unit) {
            open(register).use { s ->
                val req = "GET ${downPath(bytes)} HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nCache-Control: no-cache\r\n\r\n"
                s.getOutputStream().apply { write(req.toByteArray(Charsets.US_ASCII)); flush() }
                val input = s.getInputStream()
                val status = readHeaders(input)
                if (status !in 200..299) throw IOException("HTTP $status")
                drain(input, counter, stop)
            }
        }

        override fun upload(bytes: Long, counter: AtomicLong, stop: AtomicBoolean, register: (Closeable) -> Unit) {
            val path = upPath ?: return
            open(register).use { s ->
                val req = "POST $path HTTP/1.1\r\nHost: $host\r\nContent-Type: application/octet-stream\r\n" +
                    "Content-Length: $bytes\r\nConnection: close\r\n\r\n"
                val out = s.getOutputStream()
                out.write(req.toByteArray(Charsets.US_ASCII))
                val complete = pump(out::write, bytes, counter, stop)
                if (complete) {
                    out.flush()
                    runCatching { readHeaders(s.getInputStream()) }
                }
            }
        }

        /** A HTTP-válasz fejlécének átugrása; a státuszkódot adja vissza. */
        private fun readHeaders(input: InputStream): Int {
            val sb = StringBuilder()
            var matched = 0
            val end = "\r\n\r\n"
            while (matched < 4) {
                val b = input.read()
                if (b < 0) throw IOException("A kapcsolat a fejléc közben megszakadt")
                val c = b.toChar()
                sb.append(c)
                matched = if (c == end[matched]) matched + 1 else if (c == '\r') 1 else 0
                if (sb.length > 16_384) throw IOException("Túl hosszú HTTP-fejléc")
            }
            val statusLine = sb.lineSequence().firstOrNull() ?: ""
            return statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
        }
    }

    companion object {
        private val randomBlock: ByteArray = ByteArray(64 * 1024).also { Random(42).nextBytes(it) }

        private fun drain(input: InputStream, counter: AtomicLong, stop: AtomicBoolean) {
            val buf = ByteArray(64 * 1024)
            while (!stop.get()) {
                val n = input.read(buf)
                if (n < 0) break
                counter.addAndGet(n.toLong())
            }
        }

        /** Adat írása [total] bájtig vagy leállításig; true, ha a teljes mennyiség kiment. */
        private fun pump(write: (ByteArray, Int, Int) -> Unit, total: Long, counter: AtomicLong, stop: AtomicBoolean): Boolean {
            var written = 0L
            while (written < total) {
                if (stop.get()) return false
                val len = minOf(randomBlock.size.toLong(), total - written).toInt()
                write(randomBlock, 0, len)
                written += len
                counter.addAndGet(len.toLong())
            }
            return true
        }

        /** Egy TCP-kapcsolódás ideje (SYN -> SYN/ACK = 1 RTT) ms-ban; "connection refused" is érvényes válasz. */
        fun tcpRtt(address: InetAddress, port: Int, timeoutMs: Int = 2_000): Double? {
            val start = System.nanoTime()
            return try {
                Socket().use { it.connect(InetSocketAddress(address, port), timeoutMs) }
                (System.nanoTime() - start) / 1e6
            } catch (e: java.net.ConnectException) {
                if (e.message?.contains("refused", ignoreCase = true) == true) (System.nanoTime() - start) / 1e6 else null
            } catch (e: Exception) {
                null
            }
        }

        fun parseHostPort(raw: String, defaultPort: Int): Pair<String, Int> {
            val t = raw.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
            require(t.isNotEmpty()) { "Add meg a LAN SpeedServer címét (pl. 192.168.1.10:8765)." }
            val idx = t.lastIndexOf(':')
            return if (idx > 0 && t.substring(idx + 1).toIntOrNull() != null) {
                t.substring(0, idx) to t.substring(idx + 1).toInt()
            } else {
                t to defaultPort
            }
        }

        fun median(values: List<Double>): Double? {
            if (values.isEmpty()) return null
            val s = values.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
        }

        fun percentile(values: List<Double>, p: Double): Double? {
            if (values.isEmpty()) return null
            val s = values.sorted()
            val idx = ((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)
            return s[idx]
        }

        fun fmt(v: Double?): String = if (v == null) "?" else if (v >= 100) "%.0f".format(v) else "%.1f".format(v)
    }
}
