// Verzio: v0.1.0 - 2026-09-27
package hu.lordathis.networktools.speed

import hu.lordathis.networktools.network.ConnectionKind

/**
 * A Sebességteszt modul (M1 sávszélesség, M2 stabilitás/csomagvesztés, M3 folyamatos ping/kábelteszt,
 * valamint a külön kapcsolóval bekapcsolható LAN-mérés) közös adatmodelljei.
 */

/** Egy kiértékelési megállapítás súlyossága - a UI ez alapján színez (zöld/kék/sárga/piros). */
enum class Severity { OK, INFO, WARN, BAD }

/** Egy megállapítás a mérés eredményéről (pl. "Fast Ethernet korlát gyanú"). */
data class Finding(val severity: Severity, val text: String)

// ------------------------------------------------------------------------------------------ Link

/**
 * A jelenlegi kapcsolat "papíron" ismert adatai - EHHEZ képest értékeljük a mért sebességet.
 * A WiFi-mezők a rádiós (PHY) sebességek, nem a valódi áteresztőképesség (az jellemzően a PHY 50-65%-a).
 */
data class LinkSnapshot(
    val kind: ConnectionKind? = null,
    val wifiLinkMbps: Int? = null,
    val wifiRxMbps: Int? = null,
    val wifiTxMbps: Int? = null,
    val wifiMaxRxMbps: Int? = null,
    val wifiFreqMhz: Int? = null,
    val wifiStandard: String? = null,
    val wifiRssi: Int? = null,
    val ethernetMbps: Int? = null,
    val ethernetIface: String? = null,
    val estimatedDownMbps: Int? = null,
    val gateway: String? = null,
    val dnsServers: List<String> = emptyList(),
    val localIpv4: String? = null,
    val takenMs: Long = 0L,
) {
    val isWifi: Boolean get() = kind == ConnectionKind.WIFI
    val isEthernet: Boolean get() = kind == ConnectionKind.ETHERNET
    val band: String?
        get() = wifiFreqMhz?.let {
            when {
                it < 3000 -> "2,4 GHz"
                it < 5925 -> "5 GHz"
                else -> "6 GHz"
            }
        }
}

/** Az előfizetett (szerződés szerinti) internet-sebesség, hálózatonként megadva. */
data class ContractSpeed(val downMbps: Int?, val upMbps: Int?)

// ------------------------------------------------------------------------------------------ M1

enum class BandwidthServer(val label: String) {
    CLOUDFLARE("Cloudflare (internet)"),
    CUSTOM("Egyedi letöltési URL"),
    LAN_SERVER("LAN SpeedServer (PC)"),
}

data class BandwidthConfig(
    val server: BandwidthServer = BandwidthServer.CLOUDFLARE,
    /** CUSTOM módban: egy nagy fájl közvetlen letöltési címe (feltöltés ilyenkor nincs). */
    val customUrl: String = "",
    /** LAN_SERVER módban: "ip:port" (a win/SpeedServer.ps1 alapból 8765-ös porton figyel). */
    val lanServer: String = "",
    val connections: Int = 4,
    val durationSec: Int = 10,
    val upload: Boolean = true,
)

data class BandwidthResult(
    val serverLabel: String,
    val downloadMbps: Double?,
    /** Az 1 mp-es minták 90. percentilise - a "plafon" (Fast Ethernet detektáláshoz). */
    val downloadPeakMbps: Double?,
    val uploadMbps: Double?,
    val uploadPeakMbps: Double?,
    val idleLatencyMs: Double?,
    val loadedLatencyMs: Double?,
    val bytesDown: Long,
    val bytesUp: Long,
    val connections: Int,
)

// ------------------------------------------------------------------------------------------ M2

/**
 * A stabilitás-teszt presetjei (a to-dos_tasks.md M2 pontja szerint).
 * [packetsPerSec] és [payloadBytes] a kiküldött UDP-csomagokra vonatkozik.
 */
enum class StabilityPreset(
    val label: String,
    val packetsPerSec: Int,
    val payloadBytes: Int,
    val durationSec: Int,
    val variable: Boolean,
) {
    GENERAL_30("Általános (30 s)", 20, 64, 30, false),
    GENERAL_60("Általános (60 s)", 20, 64, 60, false),
    VOIP("VoIP / WebRTC", 50, 172, 30, false),
    GAMING("Gaming", 64, 120, 30, true),
}

/** Egy 1 másodperces ablak (a valós idejű aggregáláshoz). */
data class StabilityWindow(val second: Int, val sent: Int, val received: Int) {
    val lossPct: Double get() = if (sent == 0) 0.0 else (sent - received) * 100.0 / sent
}

data class StabilityLive(
    val elapsedSec: Int,
    val totalSec: Int,
    val sent: Int,
    val received: Int,
    val lossPct: Double,
    val lastRttMs: Double?,
    val avgRttMs: Double?,
    val jitterMs: Double?,
    val windows: List<StabilityWindow>,
)

data class StabilityResult(
    val target: String,
    val preset: StabilityPreset,
    val sent: Int,
    val received: Int,
    val late: Int,
    val lossPct: Double,
    val rttMinMs: Double?,
    val rttAvgMs: Double?,
    val rttMaxMs: Double?,
    val jitterMs: Double?,
    val worstWindowLossPct: Double,
    val targetIsLocal: Boolean,
)

// ------------------------------------------------------------------------------------------ M3

/** Egy ping-minta a grafikonhoz; [rttMs] == null: nem jött válasz (időtúllépés). */
data class PingPoint(val seq: Int, val timeMs: Long, val rttMs: Double?)

data class PingStats(
    val sent: Int = 0,
    val lost: Int = 0,
    val minMs: Double? = null,
    val avgMs: Double? = null,
    val maxMs: Double? = null,
    val jitterMs: Double? = null,
    val currentLossStreak: Int = 0,
    val maxLossStreak: Int = 0,
) {
    val lossPct: Double get() = if (sent == 0) 0.0 else lost * 100.0 / sent
}

// ------------------------------------------------------------------------------------------ LAN

/** Egy hálózaton belüli eszköz, amit a korábbi tesztek (ping-sweep, hostname, SNMP, HTTP, SSH, miner) láttak. */
data class LanHostInfo(
    val ip: String,
    val hostname: String? = null,
    val httpTitle: String? = null,
    val snmpDescr: String? = null,
    val sshBanner: String? = null,
    val minerInfo: String? = null,
    val lastSeenMs: Long = 0L,
) {
    /** Minden, ami alapján gyártóra/modellre lehet következtetni - a katalógus-egyeztetés ebben keres. */
    val fingerprint: String
        get() = listOfNotNull(hostname, httpTitle, snmpDescr, sshBanner, minerInfo).joinToString(" | ")

    val label: String
        get() = hostname ?: httpTitle ?: snmpDescr?.take(40) ?: minerInfo?.let { "miner" } ?: ip

    fun mergedWith(other: LanHostInfo): LanHostInfo = LanHostInfo(
        ip = ip,
        hostname = other.hostname ?: hostname,
        httpTitle = other.httpTitle ?: httpTitle,
        snmpDescr = other.snmpDescr ?: snmpDescr,
        sshBanner = other.sshBanner ?: sshBanner,
        minerInfo = other.minerInfo ?: minerInfo,
        lastSeenMs = maxOf(lastSeenMs, other.lastSeenMs),
    )
}

/** A gyártó-specifikus port-sebesség lista egy bejegyzése (assets + saját bővítés). */
data class DeviceSpeedEntry(
    val id: String,
    val vendor: String,
    val model: String,
    val category: String,
    /** A vezetékes port névleges sebessége (10/100/1000/2500...), null ha nincs vezetékes port. */
    val portMbps: Int?,
    /** Csak WiFi-s eszköznél: a rádió jellemző max. PHY-sebessége. */
    val wifiMbps: Int?,
    val match: List<String>,
    val confidence: String,
    val notes: String,
    /** "catalog" (a repóban lévő lista) vagy "user" (a felhasználó / saját mérés alapján felvéve). */
    val source: String = "catalog",
) {
    /** A várható felső korlát Mbps-ben (vezetékes port, ennek hiányában a WiFi). */
    val expectedMbps: Int? get() = portMbps ?: wifiMbps
}

data class LanTarget(
    val ip: String,
    val label: String,
    val role: String,
    val catalog: DeviceSpeedEntry?,
)

data class LanMeasurement(
    val ip: String,
    val label: String,
    val role: String,
    val method: String,
    val sent: Int,
    val lost: Int,
    val rttSmallMinMs: Double?,
    val rttSmallAvgMs: Double?,
    val rttLargeMinMs: Double?,
    /** Kis/nagy csomag RTT-különbségéből becsült útvonal-sebesség (kísérleti, csak ICMP-vel). */
    val pathEstimateMbps: Double?,
    val catalog: DeviceSpeedEntry?,
    val findings: List<Finding> = emptyList(),
) {
    val lossPct: Double get() = if (sent == 0) 0.0 else lost * 100.0 / sent
}

/** Egy saját mérés eredménye, ami a "saját listába" automatikusan bekerül (eszközönként a legjobb érték). */
data class MeasuredDevice(
    val networkKey: String,
    val ip: String,
    val label: String,
    val catalogId: String?,
    val lastMs: Long,
    val bestPathMbps: Double?,
    val minRttMs: Double?,
    val lastLossPct: Double,
    val runs: Int,
)

// ------------------------------------------------------------------------------------------ Előzmények

/** Egy lezárt sebességteszt-futás rövid adatai (hálózatonként), a korábbi mérésekkel való összevetéshez. */
data class SpeedHistoryEntry(
    val networkKey: String,
    val timeMs: Long,
    val type: String, // BW | UDP | PING | LAN
    val connKind: String,
    val server: String,
    val downMbps: Double? = null,
    val upMbps: Double? = null,
    val idleLatencyMs: Double? = null,
    val loadedLatencyMs: Double? = null,
    val lossPct: Double? = null,
    val jitterMs: Double? = null,
    val rttAvgMs: Double? = null,
    val linkMbps: Int? = null,
    val headline: String = "",
)
