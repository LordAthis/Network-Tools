// Verzio: v0.7.0 - 2026-09-28
package hu.lordathis.networktools.engine

import hu.lordathis.networktools.network.ArpProbe
import hu.lordathis.networktools.network.ConnectionKind
import hu.lordathis.networktools.network.GatewayTest
import hu.lordathis.networktools.network.HostInfo
import hu.lordathis.networktools.network.HttpTitleProbe
import hu.lordathis.networktools.network.MinerApiProbe
import hu.lordathis.networktools.network.NetworkIdentity
import hu.lordathis.networktools.network.PingTools
import hu.lordathis.networktools.network.PortLists
import hu.lordathis.networktools.network.PortScanTarget
import hu.lordathis.networktools.network.PortScanner
import hu.lordathis.networktools.network.SnmpProbe
import hu.lordathis.networktools.network.SshBannerProbe
import hu.lordathis.networktools.settings.AppPreferences
import hu.lordathis.networktools.speed.LanHostInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A hálózati tesztek FUTTATÓ MOTORJA. Minden teszt a hívó (AppHub) SAJÁT, hosszú életű scope-jában
 * fut ([scope] paraméter) - EZÉRT a képernyőváltás, egy panel bezárása vagy a fiókok nyitása/zárása
 * NEM állítja le és NEM szakítja meg a futó tesztet (ez volt az egyik fő elmaradt igény).
 *
 * Minden teszt kimenete:
 *  1. élőben látszik a [jobs] StateFlow-n át (a VISSZAJELZÉSEK panel felső, terminál-szerű sávja),
 *  2. a végén egy teljes átiratot kap a `log/tests/` mappa (a hálózat nevével ellátott fájlnévvel),
 *  3. egy rövid összefoglalót ([TestRunSummary]) a [quickReportStore]-ba, ami a Gyorsjelentés
 *     panelt táplálja, HÁLÓZATRA SZŰRVE.
 */
class TestEngine(
    private val testLogDir: File,
    private val quickReportStore: QuickReportStore,
    private val identity: NetworkIdentity,
    private val prefs: AppPreferences,
    private val scope: CoroutineScope,
    private val currentNetworkKey: () -> String,
    private val currentNetworkLogName: () -> String,
    private val onAppLog: (String) -> Unit,
    /** Minden rögzített összefoglaló után hívódik - az AppHub ezzel frissíti AZONNAL a Gyorsjelentést. */
    private val onSummaryRecorded: () -> Unit = {},
    /**
     * A felderítő tesztek (ping-sweep, hostname, SNMP, HTTP-cím, SSH-banner, miner API) által látott
     * LAN-eszközök - a Sebességteszt LAN-mérése ezekből választ célpontot (SpeedStore.lan_hosts.json).
     */
    private val onHostsObserved: (List<LanHostInfo>) -> Unit = {},
    /**
     * A közelmúltban (néhány percen belül) élőnek látott LAN-eszközök. Ha van ilyen, a Felderítés/
     * Szolgáltatások/Miner tesztek EZT használják, nem futtatnak mindegyik előtt saját ping-sweepet
     * (ez volt a README-ben jelzett "ismert tervezési hiba").
     */
    private val recentAliveHosts: () -> List<String> = { emptyList() },
    /** Telepítve van-e az Orbot (Tor) - a Tor-tesztekhez. */
    private val isOrbotInstalled: () -> Boolean = { false },
) {
    private val jobsState = MutableStateFlow<List<TestJob>>(emptyList())
    val jobs: StateFlow<List<TestJob>> = jobsState.asStateFlow()

    /** A futó tesztek korutinjai (testId -> Job) - a STOP (pl. folyamatos ping) ezen keresztül állítja le. */
    private val activeJobs = ConcurrentHashMap<String, Job>()

    fun isRunning(testId: String): Boolean = jobsState.value.any { it.testId == testId && it.status == JobStatus.RUNNING }

    /** Egy teszt elindítása; ha ugyanaz a teszt már fut, nem indít másodikat. */
    fun start(testId: String, label: String, shortCode: String) {
        startCustom(testId, label, shortCode) { emit -> dispatch(testId, emit) }
    }

    /**
     * Tetszőleges teszt-blokk futtatása ugyanazzal a kerettel (élő terminál, átirat a log/tests/ alá,
     * Gyorsjelentés-összefoglaló). A Sebességteszt modul ezt használja. [cancelHeadline]: leállításkor
     * (STOP) ez kerül a Gyorsjelentésbe - így a végtelenített teszt is értelmes összefoglalót kap.
     */
    fun startCustom(
        testId: String,
        label: String,
        shortCode: String,
        cancelHeadline: (() -> String)? = null,
        block: suspend (emit: (String) -> Unit) -> String,
    ): Boolean {
        if (isRunning(testId)) return false
        val jobId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        jobsState.update { it + TestJob(jobId, testId, label, shortCode, JobStatus.RUNNING, now) }
        val job = scope.launch(Dispatchers.IO) {
            var headline = "Befejezve."
            var status = JobStatus.DONE
            try {
                headline = block { line -> appendLine(jobId, line) }
            } catch (e: CancellationException) {
                headline = cancelHeadline?.let { runCatching { it() }.getOrNull() } ?: "Leállítva."
                appendLine(jobId, "Leállítva. $headline")
            } catch (e: Exception) {
                appendLine(jobId, "HIBA: ${e.message ?: e.javaClass.simpleName}")
                headline = "Hiba: ${e.message ?: e.javaClass.simpleName}"
                status = JobStatus.FAILED
            } finally {
                activeJobs.remove(testId)
            }
            finish(jobId, status, testId, label, shortCode, headline)
        }
        activeJobs[testId] = job
        return true
    }

    /** Egy futó teszt leállítása (a teszt a leállításig gyűjtött adatokkal zárul). */
    fun stop(testId: String) {
        activeJobs[testId]?.cancel()
    }

    fun clearFinished() {
        jobsState.update { it.filter { job -> job.status == JobStatus.RUNNING } }
    }

    private fun appendLine(jobId: String, line: String) {
        val stamp = LocalDateTime.now().format(TIME_FORMAT)
        jobsState.update { list ->
            list.map {
                if (it.jobId == jobId) it.copy(lines = (it.lines + "[$stamp] $line").takeLast(MAX_JOB_LINES)) else it
            }
        }
    }

    private fun finish(jobId: String, status: JobStatus, testId: String, label: String, shortCode: String, headline: String) {
        val now = System.currentTimeMillis()
        val transcript = jobsState.value.firstOrNull { it.jobId == jobId }?.lines ?: emptyList()
        jobsState.update { list -> list.map { if (it.jobId == jobId) it.copy(status = status, finishedMs = now) else it } }
        val logFileName = try {
            writeTranscript(label, transcript)
        } catch (e: Exception) {
            onAppLog("A teszt-átirat mentése sikertelen ($label): ${e.message}")
            null
        }
        quickReportStore.record(
            TestRunSummary(testId, label, shortCode, currentNetworkKey(), now, headline, logFileName)
        )
        onSummaryRecorded()
        onAppLog("$label ($shortCode) befejeződött: $headline")
    }

    private fun writeTranscript(label: String, lines: List<String>): String {
        testLogDir.mkdirs()
        val stamp = LocalDateTime.now().format(FILE_STAMP)
        val name = "${currentNetworkLogName()}_$stamp.log"
        val file = File(testLogDir, name)
        file.writeText("== $label ==\n" + lines.joinToString("\n") + "\n", Charsets.UTF_8)
        return name
    }

    // =========================================================================================
    // Teszt-dispatch - minden ág egy [emit] callback-en ír "terminál-sorokat", és egy rövid
    // fejléc-mondatot ad vissza (ez kerül a Gyorsjelentésbe).
    // =========================================================================================

    private suspend fun dispatch(testId: String, emit: (String) -> Unit): String = when (testId) {
        "adapter_info" -> runAdapterInfo(emit)
        "gateway_dns" -> runGatewayDns(emit)
        "public_ip" -> runPublicIp(emit)
        "cgnat_test" -> runCgnatTest(emit)
        "ipv6_status" -> runIpv6Status(emit)
        "device_info" -> runDeviceInfo(emit)
        "ping_sweep" -> runPingSweep(emit)
        "hostname_lookup" -> runHostnameLookup(emit)
        "snmp_probe" -> runSnmpProbe(emit)
        "dual_phase" -> runDualPhase(emit)
        "port_scan" -> runPortScan(emit)
        "http_title" -> runHttpTitle(emit)
        "ssh_banner" -> runSshBanner(emit)
        "miner_api" -> runMinerApi(emit)
        "wan_test" -> runWanTest(emit)
        "dns_timing" -> runDnsTiming(emit)
        "arp_spike" -> runArpSpike(emit)
        "tor_check" -> runTorCheck(emit)
        "tor_onion" -> runTorOnion(emit)
        "tor_lan" -> runTorLan(emit)
        else -> {
            emit("Ismeretlen teszt-azonosító: $testId")
            "Ismeretlen teszt"
        }
    }

    // ------------------------------------------------------------------ A csoport

    private fun activeWifiOrEthernet() = identity.activeConnections()
        .firstOrNull { it.kind == ConnectionKind.WIFI || it.kind == ConnectionKind.ETHERNET }

    private fun runAdapterInfo(emit: (String) -> Unit): String {
        val conns = identity.activeConnections()
        if (conns.isEmpty()) {
            emit("Nincs aktív, validált hálózati kapcsolat.")
            return "Nincs aktív kapcsolat"
        }
        for (c in conns) {
            emit("${c.kind}: sebesség ${c.linkSpeedMbps?.let { "$it Mbps" } ?: "ismeretlen"}, mért adat: ${if (c.isMetered) "igen" else "nem"}")
            for (addr in identity.localAddresses(c.networkHandle)) emit("  saját cím: ${addr.hostAddress}")
        }
        return conns.joinToString(" + ") { it.kind.name } + " aktív"
    }

    private fun runGatewayDns(emit: (String) -> Unit): String {
        val conn = activeWifiOrEthernet() ?: identity.activeConnections().firstOrNull()
        if (conn == null) {
            emit("Nincs aktív kapcsolat.")
            return "Nincs aktív kapcsolat"
        }
        val gateway = identity.gatewayAddress(conn.networkHandle)
        emit("Átjáró: ${gateway?.hostAddress ?: "ismeretlen"}")
        val dns = identity.dnsServers(conn.networkHandle)
        if (dns.isEmpty()) emit("Nincs DNS-szerver lekérdezve.") else dns.forEach { emit("DNS-szerver: ${it.hostAddress}") }
        return "Átjáró: ${gateway?.hostAddress ?: "?"}, ${dns.size} DNS-szerver"
    }

    private suspend fun runPublicIp(emit: (String) -> Unit): String {
        emit("Publikus IP lekérdezése...")
        val ip = fetchPublicIp()
        return if (ip != null) {
            emit("Publikus IP: $ip")
            "Publikus IP: $ip"
        } else {
            emit("A publikus IP lekérdezése sikertelen.")
            "Sikertelen"
        }
    }

    private suspend fun runCgnatTest(emit: (String) -> Unit): String {
        val ip = fetchPublicIp()
        if (ip == null) {
            emit("A publikus IP lekérdezése sikertelen, a CGNAT-teszt nem futtatható.")
            return "Sikertelen"
        }
        emit("Publikus IP: $ip")
        val cgnat = isCgnatRange(ip)
        val privateRange = isPrivateRange(ip)
        val verdict = when {
            privateRange -> "MAGÁN tartomány (RFC 1918) - a szolgáltató valószínűleg NAT mögé tesz"
            cgnat -> "CGNAT tartomány (RFC 6598, 100.64.0.0/10) - nagy valószínűséggel operátor-szintű NAT mögött vagy"
            else -> "valódi publikus cím (nem CGNAT, nem magán tartomány)"
        }
        emit("Eredmény: $verdict")
        return verdict
    }

    private fun runIpv6Status(emit: (String) -> Unit): String {
        val conn = identity.activeConnections().firstOrNull()
        if (conn == null) {
            emit("Nincs aktív kapcsolat.")
            return "Nincs aktív kapcsolat"
        }
        val addrs = identity.localAddresses(conn.networkHandle).filter { it is java.net.Inet6Address }
        val globalIpv6 = addrs.filter { !it.isLinkLocalAddress && !it.isLoopbackAddress }
        globalIpv6.forEach { emit("Globális IPv6 cím: ${it.hostAddress}") }
        return if (globalIpv6.isNotEmpty()) "Van globális IPv6 cím (${globalIpv6.size} db)" else {
            emit("Nincs globális IPv6 cím.")
            "Nincs IPv6"
        }
    }

    private fun runDeviceInfo(emit: (String) -> Unit): String {
        HostInfo.deviceSummary().forEach { (k, v) -> emit("$k: $v") }
        return "Eszközinfó lekérdezve"
    }

    private suspend fun runPingSweep(emit: (String) -> Unit): String {
        val hosts = targetHosts(emit) ?: return "Nincs vizsgálható alháló"
        emit("Ping-sweep indul: ${hosts.size} cím, egyidejűség: ${prefs.scanConcurrency}")
        val alive = PingTools.sweep(hosts, concurrency = prefs.scanConcurrency) { host, isAlive, done, total ->
            if (isAlive) emit("ÉLŐ: $host")
            if (done % 50 == 0 || done == total) emit("...vizsgálva: $done/$total")
        }
        emit("Kész: ${alive.size} élő host a(z) ${hosts.size}-ból.")
        val seenAt = System.currentTimeMillis()
        observe(alive.map { LanHostInfo(ip = it, lastSeenMs = seenAt) })
        return "${alive.size} élő host"
    }

    private suspend fun runHostnameLookup(emit: (String) -> Unit): String {
        val hosts = aliveHosts(emit) ?: return "Nincs vizsgálható alháló"
        if (hosts.isEmpty()) {
            emit("Nincs élő host, amin hostname-feloldást lehetne próbálni.")
            return "Nincs élő host"
        }
        var resolved = 0
        val seen = ArrayList<LanHostInfo>()
        for (host in hosts) {
            val name = HostInfo.reverseLookup(host)
            if (name != null) {
                emit("$host -> $name")
                resolved++
            }
            seen += LanHostInfo(ip = host, hostname = name, lastSeenMs = System.currentTimeMillis())
        }
        observe(seen)
        emit("Kész: $resolved/${hosts.size} host kapott nevet.")
        return "$resolved/${hosts.size} host nevesítve"
    }

    private suspend fun runSnmpProbe(emit: (String) -> Unit): String {
        val hosts = aliveHosts(emit)
            ?: return "Nincs vizsgálható alháló"
        var found = 0
        for (host in hosts) {
            val result = SnmpProbe.query(host)
            if (result != null) {
                emit("$host - SNMP válasz: ${result.sysDescr ?: result.sysName ?: "(üres)"}")
                found++
                val descr = listOfNotNull(result.sysName, result.sysDescr).joinToString(" ").ifBlank { null }
                observe(listOf(LanHostInfo(ip = host, snmpDescr = descr, lastSeenMs = System.currentTimeMillis())))
            }
        }
        emit("Kész: $found eszköz válaszolt SNMP-re a(z) ${hosts.size} élő hostból.")
        return "$found SNMP-eszköz"
    }

    private suspend fun runDualPhase(emit: (String) -> Unit): String {
        val conns = identity.activeConnections()
        val wifi = conns.firstOrNull { it.kind == ConnectionKind.WIFI }
        val cellular = conns.firstOrNull { it.kind == ConnectionKind.CELLULAR }
        if (wifi == null && cellular == null) {
            emit("Nincs sem WiFi, sem mobilnet kapcsolat.")
            return "Nincs vizsgálható kapcsolat"
        }
        val results = StringBuilder()
        for ((label, conn) in listOf("WiFi" to wifi, "Mobilnet" to cellular)) {
            if (conn == null) {
                emit("$label: nem aktív, kihagyva.")
                continue
            }
            emit("--- $label fázis ---")
            val gateway = identity.gatewayAddress(conn.networkHandle)
            emit("$label átjáró: ${gateway?.hostAddress ?: "ismeretlen"}")
            val ipv6 = identity.localAddresses(conn.networkHandle).any { it is java.net.Inet6Address && !it.isLinkLocalAddress }
            emit("$label IPv6: ${if (ipv6) "van" else "nincs"}")
            results.append("$label: IPv6=${if (ipv6) "igen" else "nem"}; ")
        }
        return results.toString().trim()
    }

    // ------------------------------------------------------------------ B/C csoport (port/szolgáltatás)

    private suspend fun runPortScan(emit: (String) -> Unit): String {
        val hosts = aliveHosts(emit)
            ?: return "Nincs vizsgálható alháló"
        if (hosts.isEmpty()) {
            emit("Nincs élő host a port-scanhez.")
            return "Nincs élő host"
        }
        val target = portScanTarget()
        emit("Port-scan: ${hosts.size} host, mód: ${prefs.portScanMode}")
        var totalOpen = 0
        for (host in hosts) {
            val open = PortScanner.scanHost(host, target, concurrency = prefs.scanConcurrency)
            if (open.isNotEmpty()) {
                emit("$host - nyitott portok: ${open.joinToString(", ") { it.port.toString() }}")
                totalOpen += open.size
            }
            observe(
                listOf(
                    LanHostInfo(
                        ip = host,
                        openPorts = open.map { it.port }.sorted().joinToString(", ").ifEmpty { "-" },
                        lastSeenMs = System.currentTimeMillis(),
                    )
                )
            )
        }
        emit("Kész: összesen $totalOpen nyitott port.")
        return "$totalOpen nyitott port ${hosts.size} hoszton"
    }

    private suspend fun runHttpTitle(emit: (String) -> Unit): String {
        val hosts = aliveHosts(emit)
            ?: return "Nincs vizsgálható alháló"
        var found = 0
        for (host in hosts) {
            for (port in listOf(80, 8080)) {
                val title = HttpTitleProbe.fetchTitle(host, port)
                if (title != null) {
                    emit("$host:$port - \"$title\"")
                    found++
                    observe(listOf(LanHostInfo(ip = host, httpTitle = title, lastSeenMs = System.currentTimeMillis())))
                }
            }
        }
        emit("Kész: $found web-felület találat.")
        return "$found web-UI"
    }

    private suspend fun runSshBanner(emit: (String) -> Unit): String {
        val hosts = aliveHosts(emit)
            ?: return "Nincs vizsgálható alháló"
        var found = 0
        for (host in hosts) {
            val banner = SshBannerProbe.fetchBanner(host)
            if (banner != null) {
                emit("$host - $banner")
                found++
                observe(listOf(LanHostInfo(ip = host, sshBanner = banner, lastSeenMs = System.currentTimeMillis())))
            }
        }
        emit("Kész: $found SSH-szolgáltatás.")
        return "$found SSH-host"
    }

    private suspend fun runMinerApi(emit: (String) -> Unit): String {
        val hosts = aliveHosts(emit)
            ?: return "Nincs vizsgálható alháló"
        var found = 0
        for (host in hosts) {
            val result = MinerApiProbe.probe(host)
            if (result != null) {
                emit("$host:${result.port} - miner API válaszolt (${result.rawResponse.take(120)})")
                found++
                observe(listOf(LanHostInfo(ip = host, minerInfo = result.rawResponse.take(300), lastSeenMs = System.currentTimeMillis())))
            }
        }
        emit("Kész: $found valószínű miner.")
        return "$found miner-gyanús eszköz"
    }

    // ------------------------------------------------------------------ D csoport (útvonal/kapcsolat)

    private suspend fun runWanTest(emit: (String) -> Unit): String {
        val results = GatewayTest.testWan()
        for (r in results) emit("${r.target}: ${r.succeeded}/${r.attempts} sikeres (${r.lossPercent}% csomagvesztés)")
        val allReachable = results.all { it.reachable }
        return if (allReachable) "Internet elérhető" else "Internet-elérhetőségi probléma"
    }

    private suspend fun runDnsTiming(emit: (String) -> Unit): String {
        val r = GatewayTest.dnsResolveTiming()
        return if (r.resolvedIp != null) {
            emit("${r.hostname} -> ${r.resolvedIp} (${r.millis} ms)")
            "${r.millis} ms"
        } else {
            emit("A DNS-feloldás sikertelen (${r.hostname}).")
            "Sikertelen"
        }
    }

    // ------------------------------------------------------------------ Tor (Orbot mellé telepítve)

    /** Orbot megléte + fut-e a proxyja; false-nál már ki is írta a teendőt. */
    private fun orbotReady(emit: (String) -> Unit): Boolean {
        if (!isOrbotInstalled()) {
            emit("Az Orbot (Tor) nincs telepítve - a Tor-tesztekhez telepítsd a Play Áruházból (org.torproject.android).")
            return false
        }
        emit("Orbot telepítve.")
        if (!hu.lordathis.networktools.tor.Orbot.proxyReachable()) {
            emit("Az Orbot proxyja (127.0.0.1:${hu.lordathis.networktools.tor.Orbot.HTTP_PROXY_PORT}) nem fut - indítsd el az Orbotot (Csatlakozás), majd futtasd újra.")
            return false
        }
        emit("Orbot proxy fut (127.0.0.1:${hu.lordathis.networktools.tor.Orbot.HTTP_PROXY_PORT}).")
        return true
    }

    private suspend fun runTorCheck(emit: (String) -> Unit): String {
        val direct = fetchPublicIp()
        if (direct != null) {
            val cg = when {
                isCgnatRange(direct) -> "CGNAT (100.64.0.0/10)"
                isPrivateRange(direct) -> "magán tartomány (NAT mögött)"
                else -> "valódi publikus cím"
            }
            emit("Közvetlen publikus IP: $direct - $cg")
        } else {
            emit("A közvetlen publikus IP nem kérdezhető le.")
        }
        if (!orbotReady(emit)) return if (isOrbotInstalled()) "Orbot telepítve, de nem fut" else "Orbot nincs telepítve"
        emit("Tor-kapcsolat ellenőrzése (check.torproject.org, max. 45 s)...")
        val r = hu.lordathis.networktools.tor.TorProbe.fetch(hu.lordathis.networktools.tor.TorProbe.CHECK_URL, viaTor = true, timeoutMs = 45_000)
        if (!r.ok) {
            emit("Sikertelen (${r.millis} ms): ${r.error ?: "HTTP ${r.code}"}")
            emit("Ha az Orbot nem tud csatlakozni, a szolgáltató blokkolhatja a Tor-t: az Orbotban kapcsold be a hidakat (obfs4 / Snowflake).")
            return "Tor NEM érhető el"
        }
        val (isTor, exitIp) = hu.lordathis.networktools.tor.TorProbe.parseCheck(r.body)
        emit("Válasz ${r.millis} ms alatt - Tor-on megy: ${if (isTor) "IGEN" else "NEM"}, kilépő IP: ${exitIp ?: "?"}")
        if (direct != null && exitIp != null) {
            emit(if (direct == exitIp) "FIGYELEM: a kilépő IP megegyezik a közvetlennel - a forgalom NEM a Tor-on ment." else "A kilépő IP eltér a közvetlentől - rendben.")
        }
        return if (isTor) "Tor OK (${r.millis} ms), kilépő IP: ${exitIp ?: "?"}" else "Válaszolt, de NEM Tor-on"
    }

    private suspend fun runTorOnion(emit: (String) -> Unit): String {
        if (!orbotReady(emit)) return if (isOrbotInstalled()) "Orbot telepítve, de nem fut" else "Orbot nincs telepítve"
        val url = hu.lordathis.networktools.tor.TorProbe.ONION_TEST_URL
        emit("Megnyitás: $url (max. 60 s)...")
        val r = hu.lordathis.networktools.tor.TorProbe.fetch(url, viaTor = true, timeoutMs = 60_000, maxBody = 2_000)
        return if (r.ok) {
            val title = r.body?.let { Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            emit("Sikerült: HTTP ${r.code}, ${r.millis} ms" + (title?.let { ", cím: \"$it\"" } ?: ""))
            ".onion elérhető (${r.millis} ms)"
        } else {
            emit("Sikertelen (${r.millis} ms): ${r.error ?: "HTTP ${r.code}"}")
            ".onion NEM érhető el"
        }
    }

    private suspend fun runTorLan(emit: (String) -> Unit): String {
        val hosts = aliveHosts(emit) ?: return "Nincs vizsgálható alháló"
        if (hosts.isEmpty()) {
            emit("Nincs élő eszköz.")
            return "Nincs élő eszköz"
        }
        val torPorts = mapOf(
            9050 to "Tor-kliens SOCKS",
            9150 to "Tor Browser SOCKS",
            9051 to "Tor vezérlőport",
            9001 to "Tor relé ORPort",
            9030 to "Tor DirPort",
        )
        emit("Tor-portok keresése ${hosts.size} eszközön: ${torPorts.keys.joinToString(", ")}")
        var suspicious = 0
        for (host in hosts) {
            val open = PortScanner.scanHost(host, PortScanTarget.ListPorts(torPorts.keys.toList()), concurrency = 8, timeoutMs = 400)
            if (open.isNotEmpty()) {
                suspicious++
                emit("GYANÚS: $host - " + open.joinToString("; ") { "${it.port} (${torPorts[it.port]})" })
            }
        }
        if (suspicious > 0) {
            emit("Ha ezeken az eszközökön nem te telepítettél Tor-t: rejtett távoli elérés lehet (gyártói \"hátsó ajtó\" vagy fertőzés). Érdemes firmware-t ellenőrizni/frissíteni, és a routeren a kimenő forgalmát figyelni.")
        } else {
            emit("Egyik eszközön sem találtam nyitott Tor-portot. (Egy csak KIFELÉ kapcsolódó Tor-kliens portot nem nyit - azt a router forgalmi naplója mutatná.)")
        }
        return if (suspicious > 0) "$suspicious eszközön Tor-port [FIGYELEM]" else "Nincs Tor-port a LAN-on"
    }

    // ------------------------------------------------------------------ ARP-spike

    private suspend fun runArpSpike(emit: (String) -> Unit): String {
        emit("ARP-tábla olvashatósági teszt indul (/proc/net/arp)...")
        val result = ArpProbe.run()
        emit("Fájl olvasható: ${result.fileReadable}")
        if (result.errorMessage != null) emit("Megjegyzés: ${result.errorMessage}")
        emit("Sorok száma: ${result.lineCount}, bejegyzés-minta: ${result.sampleEntries.size}")
        result.sampleEntries.forEach { emit("  ${it.ip} -> ${it.mac} (${it.device})") }
        return if (result.fileReadable && result.sampleEntries.isNotEmpty()) {
            "ARP OLVASHATÓ (${result.sampleEntries.size} bejegyzés)"
        } else if (result.fileReadable) {
            "ARP fájl olvasható, de üres"
        } else {
            "ARP NEM olvasható"
        }
    }

    // ------------------------------------------------------------------ Segédek

    /** Élő célok: a friss (megosztott) ping-sweep eredmény, vagy - ha nincs - egy új sweep. */
    private suspend fun aliveHosts(emit: (String) -> Unit): List<String>? {
        val all = targetHosts(emit) ?: return null
        val allSet = all.toHashSet()
        val recent = try {
            recentAliveHosts().filter { it in allSet }
        } catch (e: Exception) {
            emptyList()
        }
        if (recent.isNotEmpty()) {
            emit("A legutóbbi ping-sweep eredményét használom (${recent.size} élő eszköz) - teljesen friss listához futtasd újra a Ping-sweep-et.")
            return recent
        }
        emit("Élő eszközök keresése (ping-sweep, ${all.size} cím)...")
        val alive = PingTools.sweep(all, concurrency = prefs.scanConcurrency)
        val seenAt = System.currentTimeMillis()
        observe(alive.map { LanHostInfo(ip = it, lastSeenMs = seenAt) })
        return alive
    }

    private fun observe(hosts: List<LanHostInfo>) {
        if (hosts.isEmpty()) return
        try {
            onHostsObserved(hosts)
        } catch (e: Exception) {
            onAppLog("LAN-eszközlista frissítése sikertelen: ${e.message}")
        }
    }

    /** A jelenlegi (WiFi/Ethernet) hálózat /24-es célpontjai + a Hálózati beállításokban megadott extra alhálók. */
    private fun targetHosts(emit: (String) -> Unit): List<String>? {
        val conn = activeWifiOrEthernet()
        val hosts = ArrayList<String>()
        if (conn != null) {
            val ipv4 = identity.localAddresses(conn.networkHandle).firstOrNull { it is java.net.Inet4Address }
            if (ipv4 != null) hosts += identity.subnetHosts(ipv4)
        }
        val extra = prefs.extraSubnets.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in extra) {
            val base = line.substringBefore("/").substringBeforeLast(".")
            if (base.count { it == '.' } == 2) hosts += (1..254).map { "$base.$it" }
        }
        if (hosts.isEmpty()) {
            emit("Nincs vizsgálható alháló (nincs WiFi/Ethernet, és nincs extra alháló megadva a Hálózati beállításokban).")
            return null
        }
        return hosts.distinct()
    }

    private fun portScanTarget(): PortScanTarget = if (prefs.portScanMode == "ALL") {
        PortScanTarget.AllPorts
    } else {
        val custom = prefs.customPortList.split(",").mapNotNull { it.trim().toIntOrNull() }
        PortScanTarget.ListPorts(custom.ifEmpty { PortLists.DEFAULT })
    }

    private suspend fun fetchPublicIp(): String? = try {
        withContextIo { java.net.URL("https://api.ipify.org").readText().trim() }
            .takeIf { it.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) }
    } catch (e: Exception) {
        null
    }

    private suspend fun <T> withContextIo(block: () -> T): T =
        kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    private fun isPrivateRange(ip: String): Boolean {
        val p = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return (p[0] == 10) ||
            (p[0] == 172 && p[1] in 16..31) ||
            (p[0] == 192 && p[1] == 168)
    }

    private fun isCgnatRange(ip: String): Boolean {
        val p = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 100 && p[1] in 64..127
    }

    companion object {
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        /** Egy job terminál-sorainak felső korlátja (a végtelenített tesztek, pl. folyamatos ping miatt). */
        private const val MAX_JOB_LINES = 3000
    }
}
