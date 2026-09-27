// Verzio: v0.1.0 - 2026-09-28
package hu.lordathis.networktools.link

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import hu.lordathis.networktools.BuildConfig
import hu.lordathis.networktools.settings.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.UUID

/**
 * Linkelés - Android-oldali megvalósítás (v1, előkészítés). Lásd [LinkProtocol] és LINK_PROTOCOL.md.
 *
 *  - [setVisible] (Beállítások > Linkelés > Látható): egy háttérszál az UDP 47800-as porton figyel, és
 *    válaszol a HELLO / PING üzenetekre - így egy másik telefon (vagy később a Windowsos változat) megtalálja
 *    és mérni tudja ezt a telefont.
 *  - [discover]: HELLO broadcast a helyi hálózatra + közvetlenül a megadott (távoli) címekre, a válaszokból
 *    a társ-lista (RTT-vel).
 *  - [ping]: UDP visszhang-mérés egy társ felé (RTT, veszteség) - a pontosabb kábelteszt alapja.
 */
class LinkService(
    private val context: Context,
    private val prefs: AppPreferences,
    private val log: (String) -> Unit,
) {
    private val peersState = MutableStateFlow<List<LinkPeer>>(emptyList())
    val peers: StateFlow<List<LinkPeer>> = peersState.asStateFlow()

    private val visibleState = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = visibleState.asStateFlow()

    @Volatile
    private var responderSocket: DatagramSocket? = null

    @Volatile
    private var responderThread: Thread? = null

    /** Ennek a példánynak az állandó azonosítója. */
    val nodeId: String
        get() {
            val existing = prefs.linkNodeId
            if (existing.isNotBlank()) return existing
            val id = UUID.randomUUID().toString()
            prefs.linkNodeId = id
            return id
        }

    val nodeName: String
        get() = prefs.linkNodeName.ifBlank { "${Build.MANUFACTURER} ${Build.MODEL}".trim() }

    private fun self(type: LinkProtocol.Type, seq: Int = 0, replyTo: String? = null, pad: String? = null) =
        LinkProtocol.Message(
            type = type,
            nodeId = nodeId,
            name = nodeName,
            platform = "android",
            app = BuildConfig.VERSION_NAME,
            caps = listOf("hello", "echo"),
            seq = seq,
            replyTo = replyTo,
            pad = pad,
        )

    // ------------------------------------------------------------------------------ Láthatóság

    @Synchronized
    fun setVisible(on: Boolean) {
        prefs.linkVisible = on
        if (on) startResponder() else stopResponder()
    }

    private fun startResponder() {
        if (responderSocket != null) return
        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(LinkProtocol.UDP_PORT))
                soTimeout = 1_000
            }
        } catch (e: Exception) {
            log("Linkelés: az UDP ${LinkProtocol.UDP_PORT}-es port nem nyitható meg (${e.message}) - a láthatóság nem indult el.")
            visibleState.value = false
            return
        }
        responderSocket = socket
        visibleState.value = true
        log("Linkelés: látható ($nodeName), UDP ${LinkProtocol.UDP_PORT} figyelve.")
        responderThread = Thread {
            val buf = ByteArray(4096)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    break
                }
                val msg = LinkProtocol.decode(packet.data, packet.length) ?: continue
                if (msg.nodeId == nodeId) continue // a saját broadcastunk
                val reply = when (msg.type) {
                    LinkProtocol.Type.HELLO -> self(LinkProtocol.Type.HELLO_REPLY, msg.seq, replyTo = msg.nodeId)
                    LinkProtocol.Type.PING -> self(LinkProtocol.Type.PONG, msg.seq, replyTo = msg.nodeId, pad = msg.pad)
                    else -> null
                }
                if (msg.type == LinkProtocol.Type.HELLO || msg.type == LinkProtocol.Type.HELLO_REPLY) {
                    rememberPeer(msg, packet.address.hostAddress ?: "?", packet.port, "bejövő", null)
                }
                if (reply != null) {
                    val bytes = LinkProtocol.encode(reply)
                    try {
                        socket.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
                    } catch (e: Exception) {
                        // a válasz elveszhet - UDP
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "networktools-link-responder"
            start()
        }
    }

    private fun stopResponder() {
        val s = responderSocket ?: run {
            visibleState.value = false
            return
        }
        responderSocket = null
        runCatching { s.close() }
        runCatching { responderThread?.join(1_500) }
        responderThread = null
        visibleState.value = false
        log("Linkelés: láthatóság kikapcsolva.")
    }

    fun close() = stopResponder()

    // ------------------------------------------------------------------------------ Felfedezés

    /**
     * HELLO a helyi hálózatra (broadcast) + a [remoteTargets] címekre ("host" vagy "host:port").
     * [waitMs] ideig gyűjti a válaszokat.
     */
    suspend fun discover(remoteTargets: List<String>, waitMs: Long = 2_500): List<LinkPeer> = withContext(Dispatchers.IO) {
        val lock = multicastLock()
        val found = LinkedHashMap<String, LinkPeer>()
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 300
                val targets = ArrayList<Pair<InetAddress, Int>>()
                runCatching { targets += InetAddress.getByName("255.255.255.255") to LinkProtocol.UDP_PORT }
                subnetBroadcasts().forEach { targets += it to LinkProtocol.UDP_PORT }
                val remote = ArrayList<Pair<InetAddress, Int>>()
                for (raw in remoteTargets.map { it.trim() }.filter { it.isNotEmpty() }) {
                    val hasPort = raw.contains(':') && raw.substringAfterLast(':').toIntOrNull() != null
                    val host = if (hasPort) raw.substringBeforeLast(':') else raw
                    val port = if (hasPort) raw.substringAfterLast(':').toInt() else LinkProtocol.UDP_PORT
                    runCatching { remote += InetAddress.getByName(host) to port }
                        .onFailure { log("Linkelés: a cím nem oldható fel: $raw") }
                }
                targets += remote
                val remoteSet = remote.mapNotNull { it.first.hostAddress }.toSet()
                val sentAt = System.nanoTime()
                val hello = LinkProtocol.encode(self(LinkProtocol.Type.HELLO, seq = 1))
                for ((addr, port) in targets.distinct()) {
                    runCatching { socket.send(DatagramPacket(hello, hello.size, addr, port)) }
                }
                val deadline = System.currentTimeMillis() + waitMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(p)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val rtt = (System.nanoTime() - sentAt) / 1e6
                    val msg = LinkProtocol.decode(p.data, p.length) ?: continue
                    if (msg.nodeId == nodeId || msg.type != LinkProtocol.Type.HELLO_REPLY) continue
                    val ip = p.address.hostAddress ?: "?"
                    val via = if (ip in remoteSet) "közvetlen cím" else "helyi hálózat"
                    val peer = LinkPeer(msg.nodeId, msg.name, msg.platform, msg.app, msg.caps, ip, p.port, via, System.currentTimeMillis(), rtt)
                    if (found[msg.nodeId] == null) found[msg.nodeId] = peer
                }
            }
        } catch (e: Exception) {
            log("Linkelés: keresési hiba: ${e.message}")
        } finally {
            runCatching { lock?.release() }
        }
        found.values.forEach { p -> peersState.update { list -> list.filterNot { it.nodeId == p.nodeId } + p } }
        log("Linkelés: keresés kész - ${found.size} példány válaszolt.")
        found.values.toList()
    }

    /** UDP visszhang-mérés egy társ felé: [count] PING, [payloadBytes] kitöltéssel; RTT-k (null = elveszett). */
    suspend fun ping(peer: LinkPeer, count: Int = 10, payloadBytes: Int = 0): List<Double?> = withContext(Dispatchers.IO) {
        val results = ArrayList<Double?>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 1_000
                val addr = InetAddress.getByName(peer.address)
                val pad = if (payloadBytes > 0) "x".repeat(payloadBytes) else null
                val buf = ByteArray(8192)
                for (seq in 1..count) {
                    val bytes = LinkProtocol.encode(self(LinkProtocol.Type.PING, seq, pad = pad))
                    val t0 = System.nanoTime()
                    socket.send(DatagramPacket(bytes, bytes.size, addr, peer.port))
                    var rtt: Double? = null
                    val deadline = System.currentTimeMillis() + 1_000
                    while (System.currentTimeMillis() < deadline) {
                        val p = DatagramPacket(buf, buf.size)
                        try {
                            socket.receive(p)
                        } catch (e: SocketTimeoutException) {
                            break
                        }
                        val msg = LinkProtocol.decode(p.data, p.length) ?: continue
                        if (msg.type == LinkProtocol.Type.PONG && msg.seq == seq) {
                            rtt = (System.nanoTime() - t0) / 1e6
                            break
                        }
                    }
                    results += rtt
                    Thread.sleep(150)
                }
            }
        } catch (e: Exception) {
            log("Linkelés: ping hiba (${peer.address}): ${e.message}")
        }
        val ok = results.filterNotNull()
        if (ok.isNotEmpty()) {
            peersState.update { list -> list.map { if (it.nodeId == peer.nodeId) it.copy(rttMs = ok.average(), lastSeenMs = System.currentTimeMillis()) else it } }
        }
        results
    }

    fun forgetPeers() {
        peersState.value = emptyList()
    }

    private fun rememberPeer(msg: LinkProtocol.Message, ip: String, port: Int, via: String, rtt: Double?) {
        val peer = LinkPeer(msg.nodeId, msg.name, msg.platform, msg.app, msg.caps, ip, port, via, System.currentTimeMillis(), rtt)
        peersState.update { list -> list.filterNot { it.nodeId == peer.nodeId } + peer }
    }

    /** Az aktív IPv4-interfészek broadcast-címei (pl. 192.168.0.255). */
    private fun subnetBroadcasts(): List<InetAddress> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .filter { it.address is Inet4Address }
            .mapNotNull { it.broadcast }
            .distinct()
    } catch (e: Exception) {
        emptyList()
    }

    private fun multicastLock(): WifiManager.MulticastLock? = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wm?.createMulticastLock("networktools-link")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    } catch (e: Exception) {
        null
    }
}
