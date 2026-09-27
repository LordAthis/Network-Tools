// Verzio: v0.1.2 - 2026-09-28
package hu.lordathis.networktools.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.lordathis.networktools.engine.JobStatus
import hu.lordathis.networktools.engine.TestJob
import hu.lordathis.networktools.settings.AppPreferences
import hu.lordathis.networktools.speed.BandwidthConfig
import hu.lordathis.networktools.speed.BandwidthServer
import hu.lordathis.networktools.speed.Finding
import hu.lordathis.networktools.speed.LanMeasurement
import hu.lordathis.networktools.speed.PingPoint
import hu.lordathis.networktools.speed.Severity
import hu.lordathis.networktools.speed.SpeedTestController
import hu.lordathis.networktools.speed.SpeedUiState
import hu.lordathis.networktools.speed.StabilityPreset

/** A saját listába vétel (gyártó-specifikus lista bővítése) adatai a LAN-eredmény "LISTÁBA" gombjáról. */
internal data class CatalogEntryDraft(
    val vendor: String,
    val model: String,
    val category: String,
    val portMbps: Int?,
    val wifiMbps: Int?,
    val keyword: String,
    val notes: String,
)

/**
 * SEBESSÉGTESZT képernyő (a jobb fiók "Sebességteszt" gombja). Részei:
 *  - Kapcsolat és várható plafon (+ az előfizetett sebesség megadása hálózatonként),
 *  - M1 Sávszélesség, M2 Stabilitás (UDP), M3 Kábelteszt (folyamatos ping, élő grafikon),
 *  - LAN-mérés (külön kapcsolóval), gyártó-specifikus lista + saját bővítés,
 *  - Előzmények (a saját korábbi mérések ezen a hálózaton).
 */
@Composable
internal fun SpeedTestStripedPanel(
    modifier: Modifier,
    state: SpeedUiState,
    jobs: List<TestJob>,
    prefs: AppPreferences,
    onRefresh: () -> Unit,
    onSaveContract: (Int?, Int?) -> Unit,
    onStartBandwidth: (BandwidthConfig) -> Unit,
    onStartStability: (StabilityPreset, String) -> Unit,
    onStartPing: (String, Long, Boolean) -> Unit,
    onStop: (String) -> Unit,
    onStartLan: () -> Unit,
    onAddCatalogEntry: (CatalogEntryDraft) -> Unit,
    onOpenWebRtcTest: () -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }
    fun running(id: String) = jobs.any { it.testId == id && it.status == JobStatus.RUNNING }

    var catalogDialogFor by remember { mutableStateOf<LanMeasurement?>(null) }

    StripedPanel(
        modifier = modifier,
        topStripe = StripeSpec("FRISSÍTÉS", enabled = true, onClick = onRefresh),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("SEBESSÉGTESZT", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            ContextSection(state, onSaveContract)
            BandwidthSection(state, prefs, running(SpeedTestController.ID_BW), onStartBandwidth) { onStop(SpeedTestController.ID_BW) }
            StabilitySection(state, prefs, running(SpeedTestController.ID_UDP), onStartStability, { onStop(SpeedTestController.ID_UDP) }, onOpenWebRtcTest)
            CableSection(state, prefs, running(SpeedTestController.ID_PING), onStartPing) { onStop(SpeedTestController.ID_PING) }
            LanSection(
                state = state,
                prefs = prefs,
                lanRunning = running(SpeedTestController.ID_LAN),
                bwRunning = running(SpeedTestController.ID_BW),
                onStartLan = onStartLan,
                onStopLan = { onStop(SpeedTestController.ID_LAN) },
                onStartLanBandwidth = onStartBandwidth,
                onAddToCatalog = { catalogDialogFor = it },
            )
            HistorySection(state)
            Spacer(Modifier.height(8.dp))
        }
    }

    catalogDialogFor?.let { m ->
        CatalogEntryDialog(
            measurement = m,
            onDismiss = { catalogDialogFor = null },
            onSave = { draft ->
                catalogDialogFor = null
                onAddCatalogEntry(draft)
            },
        )
    }
}

// ================================================================================== Kapcsolat

@Composable
private fun ContextSection(state: SpeedUiState, onSaveContract: (Int?, Int?) -> Unit) {
    SpeedSection("KAPCSOLAT ÉS VÁRHATÓ PLAFON", AccentBlue) {
        val link = state.link
        if (link == null || link.kind == null) {
            Text("Nincs aktív hálózati kapcsolat (vagy még nem frissült).", color = WarnColor, fontSize = 11.sp)
        } else {
            InfoLine("Hálózat", state.networkName.ifBlank { "?" })
            InfoLine("Kapcsolat", when {
                link.isWifi -> "WiFi" + listOfNotNull(link.wifiStandard, link.band).joinToString(", ").let { if (it.isEmpty()) "" else " ($it)" }
                link.isEthernet -> "vezetékes (Ethernet)"
                else -> link.kind.name
            })
            if (link.isWifi) {
                val phy = if (link.wifiRxMbps != null || link.wifiTxMbps != null) {
                    "le ${link.wifiRxMbps ?: "?"} / fel ${link.wifiTxMbps ?: "?"} Mbps"
                } else {
                    "${link.wifiLinkMbps ?: "?"} Mbps"
                }
                InfoLine("WiFi PHY", phy + (link.wifiMaxRxMbps?.let { " (max. $it)" } ?: ""))
                if ((link.wifiRxMbps ?: 999) < 12 && (link.phyDownMbps ?: 0) > (link.wifiRxMbps ?: 0)) {
                    Text(
                        "A \"le\" érték az utoljára vett keret rátája - üresjáratban ilyen alacsony is lehet; a becslés a nagyobb értékkel számol.",
                        color = TextDim, fontSize = 9.sp,
                    )
                }
                link.wifiRssi?.let { InfoLine("Jelerősség", "$it dBm", if (it < -72) WarnColor else TextMain) }
            }
            if (link.isEthernet) InfoLine("Vezetékes link", link.ethernetMbps?.let { "$it Mbps" } ?: "a rendszer nem adja meg")
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Átjáró / DNS:", color = TextDim, fontSize = 10.sp, modifier = Modifier.width(110.dp))
                LinkifiedText("${link.gateway ?: "?"} / ${link.dnsServers.joinToString(", ").ifBlank { "?" }}", color = TextMain, fontSize = 10.sp)
            }
            val realistic = when {
                link.isEthernet && link.ethernetMbps != null -> "~${(link.ethernetMbps * 0.94).toInt()} Mbps (vezetékes link)"
                link.isWifi && link.phyDownMbps != null ->
                    "~${((link.phyDownMbps ?: 0) * 0.6).toInt()} Mbps (a WiFi PHY ~60%-a)"
                else -> null
            }
            if (realistic != null) InfoLine("Valós plafon (becslés)", realistic, AccentBlue)
        }
        Spacer(Modifier.height(6.dp))
        Text("Előfizetett sebesség ehhez a hálózathoz (Mbps):", color = TextDim, fontSize = 10.sp)
        var down by remember(state.networkKey, state.contract) { mutableStateOf(state.contract?.downMbps?.toString() ?: "") }
        var up by remember(state.networkKey, state.contract) { mutableStateOf(state.contract?.upMbps?.toString() ?: "") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumberField("Le", down, Modifier.weight(1f)) { down = it }
            NumberField("Fel", up, Modifier.weight(1f)) { up = it }
            PillButton("MENTÉS", enabled = true, filled = true) { onSaveContract(down.toIntOrNull(), up.toIntOrNull()) }
        }
    }
}

// ================================================================================== M1

@Composable
private fun BandwidthSection(
    state: SpeedUiState,
    prefs: AppPreferences,
    running: Boolean,
    onStart: (BandwidthConfig) -> Unit,
    onStop: () -> Unit,
) {
    var server by remember { mutableStateOf(prefs.speedServer) }
    var customUrl by remember { mutableStateOf(prefs.speedCustomUrl) }
    var connections by remember { mutableIntStateOf(prefs.speedConnections) }
    var duration by remember { mutableIntStateOf(prefs.speedDurationSec) }
    var upload by remember { mutableStateOf(prefs.speedUpload) }

    SpeedSection("M1 · SÁVSZÉLESSÉG (LE / FEL)", Accent) {
        Text(
            "Többszálú TCP le- és feltöltés; közben a terhelés alatti késleltetést (bufferbloat) is méri. " +
                "Az eredményt az előfizetéshez, a link-sebességhez és a korábbi méréseidhez hasonlítja; " +
                "~94 Mbps-os plafonnál Fast Ethernet (kábel/port) hibára figyelmeztet.",
            color = TextDim, fontSize = 10.sp,
        )
        Spacer(Modifier.height(6.dp))
        ChipRow(
            options = listOf("CLOUDFLARE" to "Cloudflare", "CUSTOM" to "Egyedi URL"),
            selected = server,
            enabled = !running,
        ) {
            server = it
            prefs.speedServer = it
        }
        if (server == "CUSTOM") {
            OutlinedTextField(
                value = customUrl,
                onValueChange = {
                    customUrl = it
                    prefs.speedCustomUrl = it
                },
                label = { Text("Nagy fájl közvetlen letöltési címe", fontSize = 10.sp) },
                singleLine = true,
                colors = appFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(4.dp))
        ChipRow(listOf(1, 4, 8).map { it.toString() to "$it szál" }, connections.toString(), !running) {
            connections = it.toInt()
            prefs.speedConnections = connections
        }
        ChipRow(listOf(10, 15, 30).map { it.toString() to "$it s" }, duration.toString(), !running) {
            duration = it.toInt()
            prefs.speedDurationSec = duration
        }
        ChipRow(listOf("1" to "Le + fel", "0" to "Csak letöltés"), if (upload) "1" else "0", !running) {
            upload = it == "1"
            prefs.speedUpload = upload
        }
        Spacer(Modifier.height(6.dp))
        StartStopRow(running, onStart = {
            val srv = if (server == "CUSTOM") BandwidthServer.CUSTOM else BandwidthServer.CLOUDFLARE
            onStart(BandwidthConfig(server = srv, customUrl = customUrl, connections = connections, durationSec = duration, upload = upload))
        }, onStop = onStop)

        if (running || state.bwSeries.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(fmt(state.bwLiveMbps), color = Accent, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text(" Mbps", color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 5.dp))
                Spacer(Modifier.weight(1f))
                Text(state.bwPhase, color = AccentBlue, fontSize = 11.sp, modifier = Modifier.padding(bottom = 5.dp))
            }
            LinearProgressIndicator(
                progress = { state.bwProgress },
                color = Accent,
                trackColor = Accent.copy(alpha = 0.2f),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Sparkline(state.bwSeries, Modifier.fillMaxWidth().height(60.dp))
        }
        state.bwResult?.let { r ->
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Metric("LETÖLTÉS", fmt(r.downloadMbps), "Mbps", Modifier.weight(1f))
                Metric("FELTÖLTÉS", fmt(r.uploadMbps), "Mbps", Modifier.weight(1f))
                Metric("KÉSL.", "${fmt(r.idleLatencyMs)}→${fmt(r.loadedLatencyMs)}", "ms", Modifier.weight(1.2f))
            }
            Text("Plafon (1 s-os csúcs, p90): le ${fmt(r.downloadPeakMbps)} / fel ${fmt(r.uploadPeakMbps)} Mbps · ${r.serverLabel}", color = TextDim, fontSize = 9.sp)
        }
        FindingList(state.bwFindings)
    }
}

// ================================================================================== M2

@Composable
private fun StabilitySection(
    state: SpeedUiState,
    prefs: AppPreferences,
    running: Boolean,
    onStart: (StabilityPreset, String) -> Unit,
    onStop: () -> Unit,
    onOpenWebRtcTest: () -> Unit,
) {
    var presetName by remember { mutableStateOf(prefs.speedStabilityPreset) }
    val preset = runCatching { StabilityPreset.valueOf(presetName) }.getOrDefault(StabilityPreset.GENERAL_30)
    val fixed = listOf("AUTO", "GATEWAY", "1.1.1.1", "8.8.8.8")
    var targetChoice by remember { mutableStateOf(if (prefs.speedUdpTarget in fixed) prefs.speedUdpTarget else "CUSTOM") }
    var custom by remember { mutableStateOf(if (prefs.speedUdpTarget in fixed) "" else prefs.speedUdpTarget) }

    SpeedSection("M2 · STABILITÁS / CSOMAGVESZTÉS (UDP)", Accent) {
        Text(
            "Nyers UDP-csomagok újraküldés nélkül (mint a WebRTC ordered:false, maxRetransmits:0): a TCP-s sebességteszt " +
                "elrejti a csomagvesztést, ez nem. Visszhang: DNS-válasz (a router vagy egy nyilvános DNS).",
            color = TextDim, fontSize = 10.sp,
        )
        Spacer(Modifier.height(6.dp))
        ChipRow(StabilityPreset.values().map { it.name to it.label }, presetName, !running) {
            presetName = it
            prefs.speedStabilityPreset = it
        }
        Text(
            if (preset.variable) "20-128 csomag/s változó ráta, 60-300 bájt, ${preset.durationSec} s"
            else "${preset.packetsPerSec} csomag/s, ~${preset.payloadBytes} bájt, ${preset.durationSec} s",
            color = TextDim, fontSize = 9.sp,
        )
        ChipRow(
            listOf("AUTO" to "Automatikus", "GATEWAY" to "Átjáró", "1.1.1.1" to "1.1.1.1", "8.8.8.8" to "8.8.8.8", "CUSTOM" to "Egyedi"),
            targetChoice, !running,
        ) {
            targetChoice = it
            if (it != "CUSTOM") prefs.speedUdpTarget = it
        }
        if (targetChoice == "CUSTOM") {
            OutlinedTextField(
                value = custom,
                onValueChange = {
                    custom = it
                    prefs.speedUdpTarget = it.trim()
                },
                label = { Text("DNS-t kiszolgáló cél IP-je", fontSize = 10.sp) },
                singleLine = true,
                colors = appFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(6.dp))
        StartStopRow(running, onStart = {
            onStart(preset, if (targetChoice == "CUSTOM") custom.trim() else targetChoice)
        }, onStop = onStop)

        val live = state.udpLive
        val result = state.udpResult
        if (live != null || result != null) {
            Spacer(Modifier.height(8.dp))
            val loss = result?.lossPct ?: live?.lossPct ?: 0.0
            Row(verticalAlignment = Alignment.CenterVertically) {
                LossGauge(loss, Modifier.size(120.dp))
                Spacer(Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    InfoLine("Cél", state.udpTarget ?: "?")
                    InfoLine("Csomagok", "${result?.received ?: live?.received ?: 0} / ${result?.sent ?: live?.sent ?: 0}")
                    InfoLine("RTT átl.", "${fmt(result?.rttAvgMs ?: live?.avgRttMs)} ms")
                    if (result != null) InfoLine("RTT min/max", "${fmt(result.rttMinMs)} / ${fmt(result.rttMaxMs)} ms")
                    InfoLine("Jitter", "${fmt(result?.jitterMs ?: live?.jitterMs)} ms")
                    if (live != null && result == null) InfoLine("Idő", "${live.elapsedSec} / ${live.totalSec} s")
                }
            }
            val windows = live?.windows.orEmpty()
            if (windows.isNotEmpty()) {
                Text("Veszteség 1 s-os ablakonként:", color = TextDim, fontSize = 9.sp)
                WindowBars(windows.map { it.lossPct }, Modifier.fillMaxWidth().height(28.dp))
            }
        }
        FindingList(state.udpFindings)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Böngészős WebRTC-változat:", color = TextDim, fontSize = 10.sp, modifier = Modifier.weight(1f))
            PillButton("PACKETLOSSTEST.COM", enabled = true, filled = false, onClick = onOpenWebRtcTest)
        }
    }
}

// ================================================================================== M3

@Composable
private fun CableSection(
    state: SpeedUiState,
    prefs: AppPreferences,
    running: Boolean,
    onStart: (String, Long, Boolean) -> Unit,
    onStop: () -> Unit,
) {
    val fixed = listOf("GATEWAY", "8.8.8.8", "1.1.1.1")
    var targetChoice by remember { mutableStateOf(if (prefs.speedPingTarget in fixed) prefs.speedPingTarget else "CUSTOM") }
    var custom by remember { mutableStateOf(if (prefs.speedPingTarget in fixed) "" else prefs.speedPingTarget) }
    var interval by remember { mutableIntStateOf(prefs.speedPingIntervalMs) }
    var sound by remember { mutableStateOf(prefs.speedPingSound) }

    SpeedSection("M3 · KÁBELTESZT (FOLYAMATOS PING)", Accent) {
        Text(
            "Indítsd el a tesztet, majd óvatosan mozgasd, hajlítsd meg a kábelt a csatlakozók közelében! " +
                "Ha kiesést (piros sáv + hangjelzés) tapasztalsz, a kábel cserére szorul.",
            color = TextMain, fontSize = 10.sp,
        )
        val link = state.link
        if (link != null && link.kind != null && !link.isEthernet) {
            Text(
                "Figyelem: nem vezetékes kapcsolaton vagy - így a WiFi-t is méred, nem csak a kábelt (USB-Ethernet adapterrel pontos).",
                color = WarnColor, fontSize = 10.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
        ChipRow(listOf("GATEWAY" to "Átjáró", "8.8.8.8" to "8.8.8.8", "1.1.1.1" to "1.1.1.1", "CUSTOM" to "Egyedi"), targetChoice, !running) {
            targetChoice = it
            if (it != "CUSTOM") prefs.speedPingTarget = it
        }
        if (targetChoice == "CUSTOM") {
            OutlinedTextField(
                value = custom,
                onValueChange = {
                    custom = it
                    prefs.speedPingTarget = it.trim()
                },
                label = { Text("Cél IP / név", fontSize = 10.sp) },
                singleLine = true,
                colors = appFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ChipRow(listOf(250, 500, 1000).map { it.toString() to "$it ms" }, interval.toString(), !running) {
            interval = it.toInt()
            prefs.speedPingIntervalMs = interval
        }
        ChipRow(listOf("1" to "Hangjelzés BE", "0" to "Hang KI"), if (sound) "1" else "0", !running) {
            sound = it == "1"
            prefs.speedPingSound = sound
        }
        Spacer(Modifier.height(6.dp))
        StartStopRow(running, onStart = {
            onStart(if (targetChoice == "CUSTOM") custom.trim() else targetChoice, interval.toLong(), sound)
        }, onStop = onStop)

        val stats = state.pingStats
        if (stats != null && (running || state.pingPoints.isNotEmpty())) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Metric("UTOLSÓ", fmt(state.pingPoints.lastOrNull()?.rttMs), "ms", Modifier.weight(1f))
                Metric("ÁTLAG", fmt(stats.avgMs), "ms", Modifier.weight(1f))
                Metric("KIESÉS", "${stats.lost}", "/ ${stats.sent}", Modifier.weight(1f), if (stats.lost > 0) DangerColor else Accent)
            }
            val maxRtt = state.pingPoints.mapNotNull { it.rttMs }.maxOrNull()
            Text(
                "${state.pingTarget ?: "?"} · ${state.pingMethod ?: ""} · min/max ${fmt(stats.minMs)}/${fmt(stats.maxMs)} ms · " +
                    "jitter ${fmt(stats.jitterMs)} ms · skála: ${fmt(chartMax(maxRtt))} ms",
                color = TextDim, fontSize = 9.sp,
            )
            PingChart(state.pingPoints, Modifier.fillMaxWidth().height(120.dp))
        }
        FindingList(state.pingFindings)
    }
}

// ================================================================================== LAN

@Composable
private fun LanSection(
    state: SpeedUiState,
    prefs: AppPreferences,
    lanRunning: Boolean,
    bwRunning: Boolean,
    onStartLan: () -> Unit,
    onStopLan: () -> Unit,
    onStartLanBandwidth: (BandwidthConfig) -> Unit,
    onAddToCatalog: (LanMeasurement) -> Unit,
) {
    var enabled by remember { mutableStateOf(prefs.speedLanEnabled) }
    var lanServer by remember { mutableStateOf(prefs.speedLanServer) }

    SpeedSection("HÁLÓZATON BELÜLI (LAN) MÉRÉS", AccentBlue) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Mérés a hálózaton belüli eszközökhöz képest (routerek, switchek, eszközök)",
                color = TextMain, fontSize = 11.sp, modifier = Modifier.weight(1f),
            )
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    prefs.speedLanEnabled = it
                },
                colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(alpha = 0.4f)),
            )
        }
        if (!enabled) {
            Text("Kikapcsolva. Bekapcsolás után minden ismert LAN-eszközt végigmér (kb. 2-3 s / eszköz).", color = TextDim, fontSize = 10.sp)
            return@SpeedSection
        }
        Text(
            "Célok: átjáró + DNS + a korábbi Felderítés/Szolgáltatások/Miner tesztek által látott eszközök (${state.lanTargets.size} db). " +
                "Kis és nagy (1400 B) pinggel méri az RTT-t és a veszteséget; a kettő különbségéből becsli az útvonal leglassabb " +
                "szakaszát (kísérleti). A kilógó eszközöket jelöli, és összeveti a gyártó-specifikus listával.",
            color = TextDim, fontSize = 10.sp,
        )
        Text(
            "Gyártói lista: ${state.catalogCount} alap + ${state.userCatalogCount} saját bejegyzés · saját mérések ezen a hálózaton: ${state.measured.size} eszköz",
            color = AccentBlue, fontSize = 10.sp,
        )
        Spacer(Modifier.height(6.dp))
        StartStopRow(lanRunning, onStart = onStartLan, onStop = onStopLan, startLabel = "LAN-MÉRÉS")
        if (lanRunning || state.lanResults.isNotEmpty()) {
            if (state.lanTotal > 0) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { state.lanDone.toFloat() / state.lanTotal },
                    color = AccentBlue,
                    trackColor = AccentBlue.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${state.lanDone} / ${state.lanTotal} eszköz", color = TextDim, fontSize = 9.sp)
            }
            FindingList(state.lanFindings)
            state.lanResults.forEach { m -> LanRow(m, onAddToCatalog) }
        }

        Spacer(Modifier.height(10.dp))
        Text("Valódi áteresztőképesség egy LAN-os PC-hez:", color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(
            "Indítsd el a PC-n a Network-Tools win/SpeedServer.ps1 scriptjét (Launcher 15. pont), és add meg a címét. " +
                "Így a telefon ↔ PC útvonal teljes sebessége mérhető (router/switch/kábel láncon át).",
            color = TextDim, fontSize = 10.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = lanServer,
                onValueChange = {
                    lanServer = it
                    prefs.speedLanServer = it.trim()
                },
                label = { Text("PC címe (pl. 192.168.1.10:8765)", fontSize = 10.sp) },
                singleLine = true,
                colors = appFieldColors(),
                modifier = Modifier.weight(1f),
            )
            PillButton("MÉRÉS", enabled = !bwRunning && lanServer.isNotBlank(), filled = true) {
                onStartLanBandwidth(
                    BandwidthConfig(
                        server = BandwidthServer.LAN_SERVER,
                        lanServer = lanServer.trim(),
                        connections = prefs.speedConnections,
                        durationSec = prefs.speedDurationSec,
                        upload = prefs.speedUpload,
                    )
                )
            }
        }
        Text("Az eredmény fent, az M1 szakaszban jelenik meg.", color = TextDim, fontSize = 9.sp)
    }
}

@Composable
private fun LanRow(m: LanMeasurement, onAddToCatalog: (LanMeasurement) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val worst = m.findings.maxByOrNull { it.severity.ordinal }?.severity ?: Severity.OK
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .border(1.dp, sevColor(worst).copy(alpha = 0.5f), shape)
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.label, color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                LinkifiedText("${m.ip} · ${m.role}", color = TextDim, fontSize = 9.sp)
            }
            PillButton("LISTÁBA", enabled = true, filled = false) { onAddToCatalog(m) }
        }
        Text(
            "RTT min/átl ${fmt(m.rttSmallMinMs)}/${fmt(m.rttSmallAvgMs)} ms · veszteség ${m.lost}/${m.sent}" +
                (m.rttLargeMinMs?.let { " · nagy csomag ${fmt(it)} ms" } ?: "") +
                (m.pathEstimateMbps?.let { " · útvonal ~${fmt(it)} Mbps" } ?: ""),
            color = TextMain, fontSize = 10.sp,
        )
        m.findings.forEach { f -> Text("• ${f.text}", color = sevColor(f.severity), fontSize = 9.sp) }
    }
}

@Composable
private fun CatalogEntryDialog(measurement: LanMeasurement, onDismiss: () -> Unit, onSave: (CatalogEntryDraft) -> Unit) {
    var vendor by remember { mutableStateOf(measurement.catalog?.vendor ?: "") }
    var model by remember { mutableStateOf(measurement.catalog?.model ?: "") }
    var keyword by remember { mutableStateOf(if (measurement.label != measurement.ip) measurement.label.lowercase().take(40) else "") }
    var port by remember { mutableStateOf(measurement.catalog?.portMbps?.toString() ?: if (measurement.catalog?.wifiMbps != null) "WIFI" else "100") }
    var wifi by remember { mutableStateOf(measurement.catalog?.wifiMbps?.toString() ?: "") }
    var category by remember { mutableStateOf(measurement.catalog?.category ?: "egyeb") }
    val valid = vendor.isNotBlank() && model.isNotBlank() && keyword.trim().length >= 3

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gyártó-specifikus lista bővítése", fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "${measurement.ip} (${measurement.label})" +
                        (measurement.pathEstimateMbps?.let { " - mért útvonal ~${fmt(it)} Mbps" } ?: ""),
                    fontSize = 11.sp,
                )
                OutlinedTextField(vendor, { vendor = it }, label = { Text("Gyártó") }, singleLine = true, colors = appFieldColors())
                OutlinedTextField(model, { model = it }, label = { Text("Modell / család") }, singleLine = true, colors = appFieldColors())
                OutlinedTextField(
                    keyword, { keyword = it },
                    label = { Text("Felismerő kulcsszó (hostname/cím része)") },
                    singleLine = true, colors = appFieldColors(),
                )
                Text("Vezetékes port:", fontSize = 11.sp)
                ChipRow(listOf("10", "100", "1000", "2500", "WIFI").map { it to if (it == "WIFI") "csak WiFi" else "$it Mbps" }, port, true) { port = it }
                if (port == "WIFI") {
                    OutlinedTextField(
                        wifi, { wifi = it.filter { c -> c.isDigit() } },
                        label = { Text("WiFi max. PHY (Mbps)") }, singleLine = true, colors = appFieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                ChipRow(
                    listOf("miner", "switch", "router", "ap", "iot", "tv", "nas", "pc", "egyeb").map { it to it },
                    category, true,
                ) { category = it }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(
                    CatalogEntryDraft(
                        vendor = vendor,
                        model = model,
                        category = category,
                        portMbps = port.toIntOrNull(),
                        wifiMbps = if (port == "WIFI") wifi.toIntOrNull() else null,
                        keyword = keyword,
                        notes = "Saját felvétel (${measurement.ip})" +
                            (measurement.pathEstimateMbps?.let { ", mért útvonal ~${fmt(it)} Mbps" } ?: ""),
                    )
                )
            }) { Text("MENTÉS", color = if (valid) Accent else TextDim) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("MÉGSEM", color = TextDim) } },
    )
}

// ================================================================================== Előzmények

@Composable
private fun HistorySection(state: SpeedUiState) {
    SpeedSection("ELŐZMÉNYEK (EZ A HÁLÓZAT)", AccentBlue) {
        if (state.history.isEmpty()) {
            Text("Ezen a hálózaton még nincs sebességteszt-mérés.", color = TextDim, fontSize = 10.sp)
        } else {
            state.history.sortedByDescending { it.timeMs }.take(12).forEach { h ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(h.type, color = AccentBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.headline, color = TextMain, fontSize = 10.sp)
                        Text("${formatDateTime(h.timeMs)} · ${h.connKind}", color = TextDim, fontSize = 8.sp)
                    }
                }
            }
        }
    }
}

// ================================================================================== Építőelemek

@Composable
private fun SpeedSection(title: String, color: Color, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, color.copy(alpha = 0.45f), shape)
            .padding(10.dp),
    ) {
        Text(title, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun InfoLine(label: String, value: String, valueColor: Color = TextMain) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("$label:", color = TextDim, fontSize = 10.sp, modifier = Modifier.width(110.dp))
        Text(value, color = valueColor, fontSize = 10.sp)
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String, modifier: Modifier, color: Color = Accent) {
    Column(
        modifier = modifier
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = TextDim, fontSize = 8.sp, letterSpacing = 1.sp)
        Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(unit, color = TextDim, fontSize = 8.sp)
    }
}

@Composable
private fun ChipRow(options: List<Pair<String, String>>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((key, label) in options) {
            val isSel = key == selected
            val shape = RoundedCornerShape(50)
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(if (isSel) Accent.copy(alpha = 0.22f) else Color.Transparent)
                    .border(1.dp, Accent.copy(alpha = if (isSel) 0.9f else 0.35f), shape)
                    .clickable(enabled = enabled) { onSelect(key) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(label, color = if (isSel) Accent else if (enabled) TextMain else TextDim, fontSize = 10.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun StartStopRow(running: Boolean, onStart: () -> Unit, onStop: () -> Unit, startLabel: String = "INDÍTÁS") {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        PillButton(startLabel, enabled = !running, filled = true, onClick = onStart)
        Spacer(Modifier.width(10.dp))
        PillButton("STOP", enabled = running, filled = false, onClick = onStop)
        if (running) {
            Spacer(Modifier.width(10.dp))
            StatusChip("FUT", AccentBlue)
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() }.take(6)) },
        label = { Text(label, fontSize = 10.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = appFieldColors(),
        modifier = modifier,
    )
}

@Composable
private fun FindingList(findings: List<Finding>) {
    if (findings.isEmpty()) return
    Spacer(Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (f in findings) {
            Row {
                Text(
                    when (f.severity) {
                        Severity.OK -> "✓ "
                        Severity.INFO -> "i "
                        Severity.WARN -> "! "
                        Severity.BAD -> "✗ "
                    },
                    color = sevColor(f.severity), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                )
                Text(f.text, color = sevColor(f.severity), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun sevColor(s: Severity): Color = when (s) {
    Severity.OK -> Accent
    Severity.INFO -> AccentBlue
    Severity.WARN -> WarnColor
    Severity.BAD -> DangerColor
}

// ================================================================================== Grafikonok

private const val PING_CHART_SLOTS = 240

private fun chartMax(maxRtt: Double?): Double = ((maxRtt ?: 10.0).coerceAtLeast(5.0) * 1.2)

/** Élő ping-vonaldiagram: a kieséseket teljes magasságú piros sáv jelzi. */
@Composable
private fun PingChart(points: List<PingPoint>, modifier: Modifier) {
    val lineColor = Accent
    val lossColor = DangerColor
    val gridColor = TextDim.copy(alpha = 0.25f)
    val frame = Accent.copy(alpha = 0.3f)
    val maxRtt = chartMax(points.mapNotNull { it.rttMs }.maxOrNull())
    Canvas(modifier = modifier.border(1.dp, frame, RoundedCornerShape(6.dp)).padding(4.dp)) {
        val w = size.width
        val h = size.height
        for (i in 1..3) {
            val y = h * i / 4f
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        val step = w / (PING_CHART_SLOTS - 1)
        val offset = PING_CHART_SLOTS - points.size
        val path = Path()
        var penDown = false
        points.forEachIndexed { i, p ->
            val x = (offset + i) * step
            val rtt = p.rttMs
            if (rtt == null) {
                val bw = maxOf(step, 3f)
                drawRect(lossColor.copy(alpha = 0.6f), topLeft = Offset((x - bw / 2).coerceAtLeast(0f), 0f), size = Size(bw, h))
                penDown = false
            } else {
                val y = h - (rtt / maxRtt * h).toFloat().coerceIn(0f, h)
                if (!penDown) {
                    path.moveTo(x, y)
                    penDown = true
                } else {
                    path.lineTo(x, y)
                }
            }
        }
        drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Egyszerű vonal (az M1 élő sebessége). */
@Composable
private fun Sparkline(values: List<Double>, modifier: Modifier) {
    val color = Accent
    val frame = Accent.copy(alpha = 0.3f)
    Canvas(modifier = modifier.border(1.dp, frame, RoundedCornerShape(6.dp)).padding(4.dp)) {
        if (values.size < 2) return@Canvas
        val max = (values.maxOrNull() ?: 1.0).coerceAtLeast(1.0) * 1.1
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = size.height - (v / max * size.height).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** 1 s-os ablakonkénti veszteség sávjai (zöld = 0, sárga = kevés, piros = sok). */
@Composable
private fun WindowBars(losses: List<Double>, modifier: Modifier) {
    val ok = Accent
    val warn = WarnColor
    val bad = DangerColor
    Canvas(modifier = modifier) {
        if (losses.isEmpty()) return@Canvas
        val slot = size.width / 60f
        losses.takeLast(60).forEachIndexed { i, l ->
            val c = when {
                l <= 0.0 -> ok.copy(alpha = 0.5f)
                l <= 5.0 -> warn
                else -> bad
            }
            val hFrac = if (l <= 0.0) 0.25f else (0.35f + (l / 100.0).toFloat() * 0.65f).coerceAtMost(1f)
            drawRect(c, topLeft = Offset(i * slot, size.height * (1 - hFrac)), size = Size(maxOf(slot - 1f, 1f), size.height * hFrac))
        }
    }
}

/** A veszteség %-ának skálán elfoglalt helye (0..1) - nem lineáris, hogy a kis értékek is látsszanak. */
private fun lossPosition(loss: Double): Float {
    val points = listOf(0.0 to 0.0, 0.5 to 0.3, 1.0 to 0.45, 5.0 to 0.75, 20.0 to 1.0)
    if (loss <= 0) return 0f
    for (i in 1 until points.size) {
        val (x0, y0) = points[i - 1]
        val (x1, y1) = points[i]
        if (loss <= x1) return (y0 + (loss - x0) / (x1 - x0) * (y1 - y0)).toFloat()
    }
    return 1f
}

/** Csomagveszteség "kördiagram": zöld / sárga / piros zónák, a jelenlegi érték kitöltött ívvel. */
@Composable
private fun LossGauge(lossPct: Double, modifier: Modifier) {
    val green = Accent
    val yellow = WarnColor
    val red = DangerColor
    val valueColor = when {
        lossPct <= 0.5 -> green
        lossPct <= 5.0 -> yellow
        else -> red
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2 + 2f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            val start = 150f
            val total = 240f
            val zones = listOf(
                Triple(0f, lossPosition(0.5), green),
                Triple(lossPosition(0.5), lossPosition(5.0), yellow),
                Triple(lossPosition(5.0), 1f, red),
            )
            for ((a, b, c) in zones) {
                drawArc(c.copy(alpha = 0.25f), start + a * total, (b - a) * total, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(width = stroke))
            }
            val pos = lossPosition(lossPct).coerceIn(0.01f, 1f)
            drawArc(valueColor, start, pos * total, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(width = stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("%.2f%%".format(lossPct), color = valueColor, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text("veszteség", color = TextDim, fontSize = 9.sp)
        }
    }
}

private fun fmt(v: Double?): String = hu.lordathis.networktools.speed.BandwidthTester.fmt(v)
