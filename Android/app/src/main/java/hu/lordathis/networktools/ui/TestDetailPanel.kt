// Verzio: v0.1.0 - 2026-09-27
package hu.lordathis.networktools.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.lordathis.networktools.engine.JobStatus
import hu.lordathis.networktools.engine.TestDef
import hu.lordathis.networktools.engine.TestDetailData
import hu.lordathis.networktools.engine.TestJob
import hu.lordathis.networktools.engine.TestRunSummary
import hu.lordathis.networktools.speed.LanHostInfo

/**
 * Egy manuális teszt SAJÁT panelje (a bal fiók sorára koppintva nyílik):
 *  - felül a FUTTATÁS gomb (futás közben "FUT..." és tiltott),
 *  - a teszt leírása és a legutóbbi eredmény (Gyorsjelentés, a jelenlegi hálózatra),
 *  - a teszt által a hálózaton látott adatok (eszközönként: név, SNMP, portok, web-cím, SSH, miner),
 *  - a kimenet: futás közben ÉLŐBEN, egyébként a legutóbbi elmentett átirat (log/tests/).
 * Minden futás-állapotváltáskor (indul / befejeződik) automatikusan újratölti az adatokat.
 */
@Composable
internal fun TestDetailStripedPanel(
    modifier: Modifier,
    test: TestDef,
    groupName: String,
    jobs: List<TestJob>,
    summaries: List<TestRunSummary>,
    loadDetail: suspend (String) -> TestDetailData,
    onRun: () -> Unit,
    onOpenMiners: (() -> Unit)?,
) {
    val latest = jobs.filter { it.testId == test.id }.maxByOrNull { it.startedMs }
    val running = latest?.status == JobStatus.RUNNING
    var detail by remember(test.id) { mutableStateOf<TestDetailData?>(null) }
    val summary = summaries.filter { it.testId == test.id }.maxByOrNull { it.finishedMs }
    LaunchedEffect(test.id, latest?.jobId, latest?.status, summary?.finishedMs) {
        detail = loadDetail(test.id)
    }

    StripedPanel(
        modifier = modifier,
        topStripe = StripeSpec(if (running) "FUT..." else "FUTTATÁS", enabled = !running, onClick = onRun),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column {
                Text(test.name, color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("${groupName.uppercase()} · ${test.shortCode}", color = TextDim, fontSize = 9.sp, letterSpacing = 1.sp)
                if (test.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(test.description, color = TextMain, fontSize = 11.sp)
                }
            }

            DetailBox("LEGUTÓBBI EREDMÉNY", AccentBlue) {
                when {
                    running -> Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusChip("FUT", AccentBlue)
                        Spacer(Modifier.width(8.dp))
                        Text(latest?.lines?.lastOrNull() ?: "indul...", color = TextMain, fontSize = 10.sp, maxLines = 2)
                    }
                    summary != null -> Column {
                        Text(summary.headline, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(formatDateTime(summary.finishedMs) + (summary.logFileName?.let { " · $it" } ?: ""), color = TextDim, fontSize = 9.sp)
                    }
                    else -> Text("Ezen a hálózaton még nem futott ez a teszt - indítsd el felül a FUTTATÁS gombbal.", color = TextDim, fontSize = 10.sp)
                }
            }

            val hosts = detail?.hosts.orEmpty()
            val rows = hosts.mapNotNull { h -> hostValue(test.id, h)?.let { h to it } }
            DetailBox("A HÁLÓZATON LÁTOTT ADATOK (${rows.size})", Accent) {
                if (detail == null) {
                    Text("Betöltés...", color = TextDim, fontSize = 10.sp)
                } else if (rows.isEmpty()) {
                    Text(
                        if (hosts.isEmpty()) "Még nincs adat ehhez a hálózathoz." else "Ez a teszt még nem talált ide tartozó adatot a hálózaton.",
                        color = TextDim, fontSize = 10.sp,
                    )
                } else {
                    rows.forEach { (h, value) ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(h.ip, color = AccentBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(96.dp))
                            Text(value, color = TextMain, fontSize = 10.sp, maxLines = 3)
                        }
                    }
                }
                if (onOpenMiners != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Részletes lekérdezés (hashrate, pool, hőfok, ventilátor):", color = TextDim, fontSize = 10.sp, modifier = Modifier.weight(1f))
                        PillButton("MINER'S", enabled = true, filled = false, onClick = onOpenMiners)
                    }
                }
            }

            DetailBox(if (latest != null) (if (running) "ÉLŐ KIMENET" else "KIMENET (ebben az indításban)") else "LEGUTÓBBI MENTETT KIMENET", Accent) {
                val lines = latest?.lines ?: detail?.transcript.orEmpty()
                if (latest == null && detail?.transcriptFile != null) {
                    Text(
                        "${detail?.transcriptFile} · ${detail?.transcriptTimeMs?.let { formatDateTime(it) } ?: ""}",
                        color = TextDim, fontSize = 9.sp,
                    )
                }
                if (lines.isEmpty()) {
                    Text("Még nincs kimenet.", color = TextDim, fontSize = 10.sp)
                } else {
                    lines.takeLast(300).forEach { Text(it, color = Accent, fontSize = 9.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Egy eszköz ide tartozó adata az adott teszt szempontjából (null = nem tartozik ide). */
private fun hostValue(testId: String, h: LanHostInfo): String? = when (testId) {
    "ping_sweep" -> (h.hostname ?: h.httpTitle ?: h.snmpDescr?.take(40) ?: "élő eszköz") +
        (if (h.lastSeenMs > 0) " · " + formatDateTime(h.lastSeenMs) else "")
    "hostname_lookup" -> h.hostname
    "snmp_probe" -> h.snmpDescr
    "port_scan" -> h.openPorts?.takeIf { it != "-" }
    "http_title" -> h.httpTitle
    "ssh_banner" -> h.sshBanner
    "miner_api" -> h.minerInfo?.take(160)
    else -> null
}

@Composable
private fun DetailBox(title: String, color: Color, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, color.copy(alpha = 0.45f), shape)
            .padding(10.dp),
    ) {
        Text(title, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        content()
    }
}
