// Verzio: v0.1.0 - 2026-09-27
package hu.lordathis.networktools.speed

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import hu.lordathis.networktools.engine.TestCatalog
import hu.lordathis.networktools.engine.TestEngine
import hu.lordathis.networktools.network.NetworkIdentity
import hu.lordathis.networktools.network.PingTools
import hu.lordathis.networktools.settings.AppPreferences
import hu.lordathis.networktools.speed.BandwidthTester.Companion.fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress

/** A Sebességteszt képernyő teljes állapota (a UI csak ezt figyeli). */
data class SpeedUiState(
    val link: LinkSnapshot? = null,
    val networkKey: String = "",
    val networkName: String = "",
    val contract: ContractSpeed? = null,
    val history: List<SpeedHistoryEntry> = emptyList(),
    // M1
    val bwPhase: String = "",
    val bwLiveMbps: Double? = null,
    val bwProgress: Float = 0f,
    val bwSeries: List<Double> = emptyList(),
    val bwResult: BandwidthResult? = null,
    val bwFindings: List<Finding> = emptyList(),
    // M2
    val udpTarget: String? = null,
    val udpLive: StabilityLive? = null,
    val udpResult: StabilityResult? = null,
    val udpFindings: List<Finding> = emptyList(),
    // M3
    val pingTarget: String? = null,
    val pingMethod: String? = null,
    val pingPoints: List<PingPoint> = emptyList(),
    val pingStats: PingStats? = null,
    val pingFindings: List<Finding> = emptyList(),
    // LAN
    val lanTargets: List<LanTarget> = emptyList(),
    val lanDone: Int = 0,
    val lanTotal: Int = 0,
    val lanResults: List<LanMeasurement> = emptyList(),
    val lanFindings: List<Finding> = emptyList(),
    val measured: List<MeasuredDevice> = emptyList(),
    val catalogCount: Int = 0,
    val userCatalogCount: Int = 0,
)

/**
 * A Sebességteszt modul vezérlője. A tesztek a [TestEngine] keretében futnak (élő terminál a
 * Kezdőlapon, átirat a log/tests/ alá, összefoglaló a Gyorsjelentésbe), a részletes, grafikonhoz
 * való adatokat pedig a [state] adja.
 */
class SpeedTestController(
    private val context: Context,
    private val identity: NetworkIdentity,
    private val prefs: AppPreferences,
    private val engine: TestEngine,
    private val store: SpeedStore,
    val catalog: DeviceSpeedCatalog,
    private val scope: CoroutineScope,
    private val networkKey: () -> String,
    private val networkName: () -> String,
    private val log: (String) -> Unit,
) {
    private val stateFlow = MutableStateFlow(SpeedUiState())
    val state: StateFlow<SpeedUiState> = stateFlow.asStateFlow()

    // ------------------------------------------------------------------------------ Kontextus

    /** A kapcsolat-adatok, előfizetés, előzmények és LAN-célok frissítése (a panel megnyitásakor is). */
    fun refresh() {
        scope.launch(Dispatchers.IO) { refreshNow() }
    }

    private fun refreshNow() {
        val key = networkKey()
        val link = try {
            LinkInfo.snapshot(context, identity)
        } catch (e: Exception) {
            null
        }
        val targets = buildLanTargets(link, key)
        stateFlow.update {
            it.copy(
                link = link,
                networkKey = key,
                networkName = networkName(),
                contract = store.contract(key),
                history = store.history(key).takeLast(30),
                lanTargets = targets,
                measured = catalog.measured(key),
                catalogCount = catalog.assets().size,
                userCatalogCount = catalog.userEntries().size,
            )
        }
    }

    fun saveContract(downMbps: Int?, upMbps: Int?) {
        scope.launch(Dispatchers.IO) {
            val key = networkKey()
            store.setContract(key, ContractSpeed(downMbps?.takeIf { it > 0 }, upMbps?.takeIf { it > 0 }))
            log("Előfizetett sebesség mentve (${networkName()}): le ${downMbps ?: "-"} / fel ${upMbps ?: "-"} Mbps")
            refreshNow()
        }
    }

    fun stop(testId: String) = engine.stop(testId)

    private fun def(id: String, name: String, code: String) = TestCatalog.speedTest(context, id, name, code)

    // ------------------------------------------------------------------------------ M1

    fun startBandwidth(config: BandwidthConfig): Boolean {
        val d = def(ID_BW, "Sávszélesség-teszt (M1)", "SPEED")
        return engine.startCustom(d.id, d.name, d.shortCode) { emit ->
            refreshNow()
            val before = stateFlow.value
            val link = before.link
            val key = before.networkKey
            stateFlow.update { it.copy(bwPhase = "Indul...", bwLiveMbps = null, bwProgress = 0f, bwSeries = emptyList(), bwResult = null, bwFindings = emptyList()) }
            emitContext(emit, link, before.contract)

            val tester = BandwidthTester(emit) { phase, live, progress ->
                stateFlow.update { s ->
                    s.copy(
                        bwPhase = phase,
                        bwLiveMbps = live,
                        bwProgress = progress,
                        bwSeries = if (live != null) (s.bwSeries + live).takeLast(160) else s.bwSeries,
                    )
                }
            }
            val result = tester.run(config)
            val connKind = link?.kind?.name ?: "NONE"
            val serverKey = config.server.name
            val previous = store.history(key).filter { it.type == "BW" && it.connKind == connKind && it.server == serverKey }.takeLast(20)
            val findings = SpeedAnalyzer.analyzeBandwidth(result, link, before.contract, previous, config.server == BandwidthServer.LAN_SERVER)
            emitFindings(emit, findings)
            val headline = "Le ${fmt(result.downloadMbps)} / fel ${fmt(result.uploadMbps)} Mbps, " +
                "késl. ${fmt(result.idleLatencyMs)}→${fmt(result.loadedLatencyMs)} ms" + worstTag(findings)
            store.addHistory(
                SpeedHistoryEntry(
                    networkKey = key,
                    timeMs = System.currentTimeMillis(),
                    type = "BW",
                    connKind = connKind,
                    server = serverKey,
                    downMbps = result.downloadMbps,
                    upMbps = result.uploadMbps,
                    idleLatencyMs = result.idleLatencyMs,
                    loadedLatencyMs = result.loadedLatencyMs,
                    linkMbps = linkMbps(link),
                    headline = headline,
                )
            )
            stateFlow.update { it.copy(bwResult = result, bwFindings = findings, bwPhase = "Kész", bwProgress = 1f, history = store.history(key).takeLast(30)) }
            headline
        }
    }

    // ------------------------------------------------------------------------------ M2

    fun startStability(preset: StabilityPreset, targetChoice: String): Boolean {
        val d = def(ID_UDP, "Stabilitás / csomagvesztés (M2)", "LOSS")
        return engine.startCustom(d.id, d.name, d.shortCode, cancelHeadline = { "Leállítva - " + udpHeadline() }) { emit ->
            refreshNow()
            val link = stateFlow.value.link
            val key = stateFlow.value.networkKey
            stateFlow.update { it.copy(udpLive = null, udpResult = null, udpFindings = emptyList(), udpTarget = null) }
            val tester = UdpStabilityTester(emit) { live -> stateFlow.update { it.copy(udpLive = live) } }

            val candidates = when (targetChoice) {
                "AUTO" -> (link?.dnsServers.orEmpty().filter { LinkInfo.isPrivateIpv4(it) } + listOfNotNull(link?.gateway) +
                    link?.dnsServers.orEmpty() + listOf("1.1.1.1", "8.8.8.8")).distinct()
                "GATEWAY" -> listOfNotNull(link?.gateway)
                else -> listOf(targetChoice.trim()).filter { it.isNotEmpty() }
            }
            if (candidates.isEmpty()) {
                emit("Nincs használható cél (nincs átjáró / DNS-szerver).")
                return@startCustom "Nincs cél"
            }
            emit("Célpont-választás (DNS-válasz próba): ${candidates.joinToString(", ")}")
            var chosen: String? = null
            var padded = true
            for (c in candidates) {
                if (tester.probeTarget(c, padded = true)) {
                    chosen = c
                    padded = true
                    break
                }
                if (tester.probeTarget(c, padded = false)) {
                    chosen = c
                    padded = false
                    break
                }
                emit("$c nem válaszol DNS-lekérdezésre - kihagyva.")
            }
            val target = chosen ?: run {
                emit("Egyik cél sem válaszolt UDP/DNS-re. Próbáld az 1.1.1.1 célt, vagy ellenőrizd a kapcsolatot.")
                return@startCustom "Nincs válaszoló cél"
            }
            stateFlow.update { it.copy(udpTarget = target) }
            val result = tester.run(target, preset, padded)
            val findings = SpeedAnalyzer.analyzeStability(result, link)
            emitFindings(emit, findings)
            stateFlow.update { it.copy(udpResult = result, udpFindings = findings) }
            val headline = udpHeadline() + worstTag(findings)
            store.addHistory(
                SpeedHistoryEntry(
                    networkKey = key,
                    timeMs = System.currentTimeMillis(),
                    type = "UDP",
                    connKind = link?.kind?.name ?: "NONE",
                    server = "$target / ${preset.name}",
                    lossPct = result.lossPct,
                    jitterMs = result.jitterMs,
                    rttAvgMs = result.rttAvgMs,
                    linkMbps = linkMbps(link),
                    headline = headline,
                )
            )
            stateFlow.update { it.copy(history = store.history(key).takeLast(30)) }
            headline
        }
    }

    private fun udpHeadline(): String {
        val s = stateFlow.value
        val r = s.udpResult
        return if (r != null) {
            "Veszteség ${"%.2f".format(r.lossPct)}%, RTT ${fmt(r.rttAvgMs)} ms, jitter ${fmt(r.jitterMs)} ms (${r.target})"
        } else {
            val live = s.udpLive ?: return "nincs adat"
            "Veszteség ${"%.2f".format(live.lossPct)}%, RTT ${fmt(live.avgRttMs)} ms, jitter ${fmt(live.jitterMs)} ms (részleges)"
        }
    }

    // ------------------------------------------------------------------------------ M3

    fun startPing(targetChoice: String, intervalMs: Long, sound: Boolean): Boolean {
        val d = def(ID_PING, "Kábelteszt - folyamatos ping (M3)", "CABLE")
        return engine.startCustom(d.id, d.name, d.shortCode, cancelHeadline = { finishPingSummary() }) { emit ->
            refreshNow()
            val link = stateFlow.value.link
            val target = when (targetChoice) {
                "GATEWAY" -> link?.gateway
                else -> targetChoice.trim().ifEmpty { null }
            }
            if (target == null) {
                emit("Nincs cél (az átjáró nem ismert) - adj meg egyedi címet.")
                return@startCustom "Nincs cél"
            }
            if (link != null && !link.isEthernet) {
                emit("FIGYELEM: nem vezetékes (Ethernet) kapcsolaton vagy - a teszt így a WiFi-t is méri, nem csak a kábelt.")
            }
            emit("Indítsd el a tesztet, majd óvatosan mozgasd, hajlítsd meg a kábelt a csatlakozók közelében! Ha kiesést tapasztalsz, a kábel cserére szorul.")
            val method = IcmpPing.detect(target)
            emit("Cél: $target, köz: $intervalMs ms, módszer: ${method.label}")
            stateFlow.update { it.copy(pingTarget = target, pingMethod = method.label, pingPoints = emptyList(), pingStats = PingStats(), pingFindings = emptyList()) }

            val tone: ToneGenerator? = if (sound) {
                try {
                    ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
            try {
                val pinger = ContinuousPinger(
                    emit = emit,
                    onPoint = { point, stats ->
                        stateFlow.update { it.copy(pingPoints = (it.pingPoints + point).takeLast(MAX_PING_POINTS), pingStats = stats) }
                    },
                    onLoss = { runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 180) } },
                )
                pinger.run(target, intervalMs, method)
                finishPingSummary()
            } finally {
                runCatching { tone?.release() }
            }
        }
    }

    /** A kábelteszt lezárása (STOP-nál is): kiértékelés + előzmény + a Gyorsjelentés-mondat. */
    private fun finishPingSummary(): String {
        val s = stateFlow.value
        val stats = s.pingStats ?: return "Nincs adat"
        val targetLocal = s.pingTarget?.let { LinkInfo.isPrivateIpv4(it) } ?: false
        val findings = SpeedAnalyzer.analyzePing(stats, s.link, targetLocal)
        val headline = "${stats.sent} ping, ${stats.lost} kiesés (max. ${stats.maxLossStreak} egymás után), " +
            "átl. ${fmt(stats.avgMs)} ms (${s.pingTarget ?: "?"})" + worstTag(findings)
        stateFlow.update { it.copy(pingFindings = findings) }
        try {
            store.addHistory(
                SpeedHistoryEntry(
                    networkKey = s.networkKey,
                    timeMs = System.currentTimeMillis(),
                    type = "PING",
                    connKind = s.link?.kind?.name ?: "NONE",
                    server = s.pingTarget ?: "?",
                    lossPct = stats.lossPct,
                    jitterMs = stats.jitterMs,
                    rttAvgMs = stats.avgMs,
                    linkMbps = linkMbps(s.link),
                    headline = headline,
                )
            )
            stateFlow.update { it.copy(history = store.history(s.networkKey).takeLast(30)) }
        } catch (e: Exception) {
            log("Kábelteszt-előzmény mentése sikertelen: ${e.message}")
        }
        return headline
    }

    // ------------------------------------------------------------------------------ LAN

    fun startLan(): Boolean {
        val d = def(ID_LAN, "LAN-mérés (belső eszközök)", "LAN")
        return engine.startCustom(d.id, d.name, d.shortCode) { emit ->
            refreshNow()
            var s = stateFlow.value
            val key = s.networkKey
            if (s.lanTargets.size <= 1) {
                emit("Még nincs (vagy alig van) ismert LAN-eszköz ehhez a hálózathoz - gyors ping-sweep indul...")
                val alive = quickSweep(s.link)
                val now = System.currentTimeMillis()
                store.mergeLanHosts(key, alive.map { LanHostInfo(ip = it, lastSeenMs = now) })
                emit("Ping-sweep: ${alive.size} élő eszköz. (Részletesebb azonosításhoz futtasd a bal fiók Felderítés/Szolgáltatások/Miner tesztjeit - azok eredményét is felhasználom.)")
                refreshNow()
                s = stateFlow.value
            }
            val targets = s.lanTargets.take(LanProbe.MAX_TARGETS)
            if (targets.isEmpty()) {
                emit("Nincs mérhető LAN-cél.")
                return@startCustom "Nincs LAN-cél"
            }
            stateFlow.update { it.copy(lanDone = 0, lanTotal = targets.size, lanResults = emptyList(), lanFindings = emptyList()) }
            val method = IcmpPing.detect(targets.first().ip)
            val probe = LanProbe(emit) { done, total, partial ->
                stateFlow.update { it.copy(lanDone = done, lanTotal = total, lanResults = partial) }
            }
            val raw = probe.run(targets, method)
            val (annotated, general) = SpeedAnalyzer.analyzeLan(raw, s.link)
            emitFindings(emit, general)
            annotated.forEach { m ->
                m.findings.filter { it.severity >= Severity.WARN }.forEach { emit("${m.ip} (${m.label}): ${it.text}") }
            }
            try {
                catalog.recordMeasurements(key, annotated)
                emit("Saját mérések rögzítve a saját listába (profiles/device_speeds_user.json - measured).")
            } catch (e: Exception) {
                emit("A saját mérések rögzítése sikertelen: ${e.message}")
            }
            stateFlow.update { it.copy(lanResults = annotated, lanFindings = general, measured = catalog.measured(key)) }
            val warn = annotated.count { m -> m.findings.any { it.severity >= Severity.WARN } }
            val headline = "${annotated.size} LAN-eszköz mérve, $warn gyanús" + worstTag(general)
            store.addHistory(
                SpeedHistoryEntry(
                    networkKey = key,
                    timeMs = System.currentTimeMillis(),
                    type = "LAN",
                    connKind = s.link?.kind?.name ?: "NONE",
                    server = "${annotated.size} eszköz",
                    headline = headline,
                )
            )
            stateFlow.update { it.copy(history = store.history(key).takeLast(30)) }
            headline
        }
    }

    /** Saját bejegyzés felvétele a gyártó-specifikus listába (a LAN-eredmény "LISTÁBA" gombja). */
    fun addUserCatalogEntry(vendor: String, model: String, category: String, portMbps: Int?, wifiMbps: Int?, keyword: String, notes: String) {
        scope.launch(Dispatchers.IO) {
            val kw = keyword.trim()
            val id = "user_" + (vendor + "_" + model).lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { System.currentTimeMillis().toString() }
            catalog.upsertUserEntry(
                DeviceSpeedEntry(
                    id = id,
                    vendor = vendor.trim(),
                    model = model.trim(),
                    category = category.trim().ifBlank { "egyeb" },
                    portMbps = portMbps,
                    wifiMbps = wifiMbps,
                    match = listOf(kw).filter { it.length >= 3 },
                    confidence = "sajat",
                    notes = notes.trim(),
                    source = "user",
                )
            )
            log("Gyártó-specifikus lista bővítve: ${vendor.trim()} ${model.trim()} (${portMbps?.let { "$it Mbps port" } ?: "WiFi ${wifiMbps ?: "?"} Mbps"}, kulcsszó: $kw)")
            refreshNow()
        }
    }

    // ------------------------------------------------------------------------------ segédek

    private fun buildLanTargets(link: LinkSnapshot?, key: String): List<LanTarget> {
        val list = ArrayList<LanTarget>()
        val own = link?.localIpv4
        val gw = link?.gateway?.takeIf { LinkInfo.isPrivateIpv4(it) }
        val hosts = store.lanHosts(key).associateBy { it.ip }
        if (gw != null) {
            val info = hosts[gw]
            list += LanTarget(gw, info?.label?.takeIf { it != gw } ?: "átjáró (router)", "átjáró", info?.let { catalog.match(it.fingerprint) })
        }
        link?.dnsServers.orEmpty().filter { LinkInfo.isPrivateIpv4(it) && it != gw }.forEach { dns ->
            val info = hosts[dns]
            list += LanTarget(dns, info?.label?.takeIf { it != dns } ?: "DNS-szerver", "DNS", info?.let { catalog.match(it.fingerprint) })
        }
        for (h in hosts.values) {
            if (h.ip == own || list.any { it.ip == h.ip }) continue
            list += LanTarget(h.ip, h.label, "eszköz", catalog.match(h.fingerprint))
        }
        return list
    }

    private suspend fun quickSweep(link: LinkSnapshot?): List<String> = withContext(Dispatchers.IO) {
        val ip = link?.localIpv4 ?: return@withContext emptyList()
        val addr = try {
            InetAddress.getByName(ip)
        } catch (e: Exception) {
            null
        }
        if (addr !is Inet4Address) return@withContext emptyList()
        PingTools.sweep(identity.subnetHosts(addr), concurrency = prefs.scanConcurrency, timeoutMs = 300)
            .filter { it != ip }
    }

    private fun emitContext(emit: (String) -> Unit, link: LinkSnapshot?, contract: ContractSpeed?) {
        if (link == null || link.kind == null) {
            emit("Kapcsolat: nincs aktív hálózat?")
            return
        }
        val parts = ArrayList<String>()
        parts += "Kapcsolat: ${link.kind}"
        link.wifiStandard?.let { parts += it }
        link.band?.let { parts += it }
        if (link.wifiRxMbps != null || link.wifiTxMbps != null) parts += "PHY le/fel: ${link.wifiRxMbps ?: "?"}/${link.wifiTxMbps ?: "?"} Mbps"
        else link.wifiLinkMbps?.let { parts += "PHY: $it Mbps" }
        link.wifiRssi?.let { parts += "jel: $it dBm" }
        link.ethernetMbps?.let { parts += "vezetékes link: $it Mbps" }
        emit(parts.joinToString(", "))
        emit(
            "Előfizetés ehhez a hálózathoz: " +
                if (contract == null) "nincs megadva" else "le ${contract.downMbps ?: "?"} / fel ${contract.upMbps ?: "?"} Mbps"
        )
    }

    private fun emitFindings(emit: (String) -> Unit, findings: List<Finding>) {
        if (findings.isEmpty()) return
        emit("--- Kiértékelés ---")
        findings.forEach { emit("[${sevLabel(it.severity)}] ${it.text}") }
    }

    private fun sevLabel(s: Severity) = when (s) {
        Severity.OK -> "OK"
        Severity.INFO -> "INFO"
        Severity.WARN -> "FIGYELEM"
        Severity.BAD -> "HIBA"
    }

    private fun worstTag(findings: List<Finding>): String {
        val worst = findings.maxByOrNull { it.severity.ordinal }?.severity ?: return ""
        return when (worst) {
            Severity.BAD -> " [HIBA]"
            Severity.WARN -> " [FIGYELEM]"
            else -> ""
        }
    }

    private fun linkMbps(link: LinkSnapshot?): Int? = link?.ethernetMbps ?: link?.wifiRxMbps ?: link?.wifiLinkMbps

    companion object {
        const val ID_BW = "speed_bw"
        const val ID_UDP = "speed_udp"
        const val ID_PING = "speed_ping"
        const val ID_LAN = "speed_lan"
        private const val MAX_PING_POINTS = 240
    }
}
