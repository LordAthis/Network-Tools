// Verzio: v0.5.0 - 2026-09-22
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

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
) {
    private val jobsState = MutableStateFlow<List<TestJob>>(emptyList())
    val jobs: StateFlow<List<TestJob>> = jobsState.asStateFlow()

    fun isRunning(testId: String): Boolean = jobsState.value.any { it.testId == testId && it.status == JobStatus.RUNNING }

    /** Egy teszt elindítása; ha ugyanaz a teszt már fut, nem indít másodikat. */
    fun start(testId: String, label: String, shortCode: String) {
        if (isRunning(testId)) return
        val jobId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        jobsState.update { it + TestJob(jobId, testId, label, shortCode, JobStatus.RUNNING, now) }
        scope.launch(Dispatchers.IO) {
            var headline = "Befejezve."
            var status = JobStatus.DONE
            try {
                headline = dispatch(testId) { line -> appendLine(jobId, line) }
            } catch (e: Exception) {
                appendLine(jobId, "HIBA: ${e.message ?: e.javaClass.simpleName}")
                headline = "Hiba: ${e.message ?: e.javaClass.simpleName}"
                status = JobStatus.FAILED
            }
            finish(jobId, status, testId, label, shortCode, headline)
        }
    }

    fun clearFinished() {
        jobsState.update { it.filter { job -> job.status == JobStatus.RUNNING } }
    }

    private fun appendLine(jobId: String, line: String) {
        val stamp = LocalDateTime.now().format(TIME_FORMAT)
        jobsState.update { list ->
            list.map { if (it.jobId == jobId) it.copy(lines = it.lines + "[$stamp] $line") else it }
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
        return "${alive.size} élő host"
    }

    private suspend fun runHostnameLookup(emit: (String) -> Unit): String {
        val hosts = targetHosts(emit)?.let { all ->
            // Csak a mostanra már ismert(nek tűnő) célokra van értelme - egy gyors élő-lista előbb.
            PingTools.sweep(all, concurrency = prefs.scanConcurrency)
        } ?: return "Nincs vizsgálható alháló"
        if (hosts.isEmpty()) {
            emit("Nincs élő host, amin hostname-feloldást lehetne próbálni.")
            return "Nincs élő host"
        }
        var resolved = 0
        for (host in hosts) {
            val name = HostInfo.reverseLookup(host)
            if (name != null) {
                emit("$host -> $name")
                resolved++
            }
        }
        emit("Kész: $resolved/${hosts.size} host kapott nevet.")
        return "$resolved/${hosts.size} host nevesítve"
    }

    private suspend fun runSnmpProbe(emit: (String) -> Unit): String {
        val hosts = targetHosts(emit)?.let { PingTools.sweep(it, concurrency = prefs.scanConcurrency) }
            ?: return "Nincs vizsgálható alháló"
        var found = 0
        for (host in hosts) {
            val result = SnmpProbe.query(host)
            if (result != null) {
                emit("$host - SNMP válasz: ${result.sysDescr ?: result.sysName ?: "(üres)"}")
                found++
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
        val hosts = targetHosts(emit)?.let { PingTools.sweep(it, concurrency = prefs.scanConcurrency) }
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
        }
        emit("Kész: összesen $totalOpen nyitott port.")
        return "$totalOpen nyitott port ${hosts.size} hoszton"
    }

    private suspend fun runHttpTitle(emit: (String) -> Unit): String {
        val hosts = targetHosts(emit)?.let { PingTools.sweep(it, concurrency = prefs.scanConcurrency) }
            ?: return "Nincs vizsgálható alháló"
        var found = 0
        for (host in hosts) {
            for (port in listOf(80, 8080)) {
                val title = HttpTitleProbe.fetchTitle(host, port)
                if (title != null) {
                    emit("$host:$port - \"$title\"")
                    found++
                }
            }
        }
        emit("Kész: $found web-felület találat.")
        return "$found web-UI"
    }

    private suspend fun runSshBanner(emit: (String) -> Unit): String {
        val hosts = targetHosts(emit)?.let { PingTools.sweep(it, concurrency = prefs.scanConcurrency) }
            ?: return "Nincs vizsgálható alháló"
        var found = 0
        for (host in hosts) {
            val banner = SshBannerProbe.fetchBanner(host)
            if (banner != null) {
                emit("$host - $banner")
                found++
            }
        }
        emit("Kész: $found SSH-szolgáltatás.")
        return "$found SSH-host"
    }

    private suspend fun runMinerApi(emit: (String) -> Unit): String {
        val hosts = targetHosts(emit)?.let { PingTools.sweep(it, concurrency = prefs.scanConcurrency) }
            ?: return "Nincs vizsgálható alháló"
        var found = 0
        for (host in hosts) {
            val result = MinerApiProbe.probe(host)
            if (result != null) {
                emit("$host:${result.port} - miner API válaszolt (${result.rawResponse.take(120)})")
                found++
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
    }
}
