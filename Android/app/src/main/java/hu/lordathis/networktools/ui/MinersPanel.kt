// Verzio: v0.1.1 - 2026-09-28
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.lordathis.networktools.engine.JobStatus
import hu.lordathis.networktools.engine.TestJob
import hu.lordathis.networktools.miner.MinerCandidate
import hu.lordathis.networktools.miner.MinerService
import hu.lordathis.networktools.miner.MinerStatus

/**
 * MINER'S panel (jobb fiók, ventilátor ikon): a hálózaton talált minerek egy helyen.
 *  - Jelöltek a korábbi tesztek adataiból (miner API, 4028/4433 port, név/web-cím/banner, gyártói lista);
 *  - felül a MINER-KERESÉS (a bal fiók "Miner API próba" tesztje);
 *  - minerenként részletes lekérdezés: modell, hashrate, pool(ok), hőfok, ventilátor, üzemidő - csak
 *    OLVASÓ API-parancsokkal (version/summary/pools/stats), a gépen semmit nem állít;
 *  - a web-felület megnyitása a beépített böngészőben.
 */
@Composable
internal fun MinersStripedPanel(
    modifier: Modifier,
    jobs: List<TestJob>,
    statuses: Map<String, MinerStatus>,
    loadCandidates: suspend () -> List<MinerCandidate>,
    onSearch: () -> Unit,
    onQuery: (List<String>) -> Unit,
    onOpenWeb: (String) -> Unit,
) {
    val searchJob = jobs.filter { it.testId == "miner_api" }.maxByOrNull { it.startedMs }
    val queryJob = jobs.filter { it.testId == MinerService.QUERY_TEST_ID }.maxByOrNull { it.startedMs }
    val searching = searchJob?.status == JobStatus.RUNNING
    val querying = queryJob?.status == JobStatus.RUNNING
    var candidates by remember { mutableStateOf<List<MinerCandidate>?>(null) }
    var manualIp by remember { mutableStateOf("") }
    LaunchedEffect(searchJob?.status, queryJob?.status) { candidates = loadCandidates() }

    StripedPanel(
        modifier = modifier,
        topStripe = StripeSpec(if (searching) "KERESÉS FUT..." else "MINER-KERESÉS", enabled = !searching, onClick = onSearch),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(FanIcon, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("MINER'S", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Text(
                "A korábbi tesztek (Miner API próba, port-scan, hostname, HTTP-cím, SSH-banner) által talált, valószínű " +
                    "minerek. A részletes lekérdezés csak olvas (version/summary/pools/stats), a gépen semmit nem állít át.",
                color = TextDim, fontSize = 10.sp,
            )

            val list = candidates
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        list == null -> "Betöltés..."
                        list.isEmpty() -> "Még nincs ismert miner ezen a hálózaton."
                        else -> "${list.size} miner-jelölt"
                    },
                    color = TextMain, fontSize = 11.sp, modifier = Modifier.weight(1f),
                )
                PillButton(
                    if (querying) "FUT..." else "ÖSSZES LEKÉRDEZÉSE",
                    enabled = !querying && !list.isNullOrEmpty(),
                    filled = true,
                ) { onQuery(list.orEmpty().map { it.host.ip }) }
            }
            if (list != null && list.isEmpty()) {
                Text(
                    "Indítsd el felül a MINER-KERESÉS-t (vagy a bal fiók Felderítés/Szolgáltatások tesztjeit), " +
                        "vagy add meg lent a miner IP-címét.",
                    color = TextDim, fontSize = 10.sp,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = manualIp,
                    onValueChange = { manualIp = it.trim() },
                    label = { Text("Miner IP-címe kézzel", fontSize = 10.sp) },
                    singleLine = true,
                    colors = appFieldColors(),
                    modifier = Modifier.weight(1f),
                )
                PillButton("LEKÉRDEZÉS", enabled = !querying && manualIp.count { it == '.' } == 3, filled = false) {
                    onQuery(listOf(manualIp))
                }
            }

            val shown = list.orEmpty().map { it.host.ip }
            list.orEmpty().forEach { c -> MinerCard(c.host.ip, c.host.label, c.reason, c.catalogModel, statuses[c.host.ip], querying, onQuery, onOpenWeb) }
            // Kézzel lekérdezett, a listában (még) nem szereplő minerek eredménye is látsszon.
            statuses.values.filter { it.ip !in shown }.forEach { st ->
                MinerCard(st.ip, st.model ?: st.ip, "kézi lekérdezés", null, st, querying, onQuery, onOpenWeb)
            }

            Text(
                "Még nincs kész (következő kör): hitelesítő-trezor (SSH/web belépési adatok titkosítva), AI-fotó-felismerés " +
                    "(típustábla/matrica alapján).",
                color = TextDim, fontSize = 9.sp,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MinerCard(
    ip: String,
    label: String,
    reason: String,
    catalogModel: String?,
    status: MinerStatus?,
    querying: Boolean,
    onQuery: (List<String>) -> Unit,
    onOpenWeb: (String) -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val borderColor = when {
        status == null -> Accent.copy(alpha = 0.4f)
        status.ok -> Accent.copy(alpha = 0.8f)
        else -> DangerColor.copy(alpha = 0.7f)
    }
    Column(
        modifier = Modifier.fillMaxWidth().border(1.dp, borderColor, shape).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(status?.model ?: catalogModel ?: label, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                LinkifiedText("$ip · $reason", color = TextDim, fontSize = 9.sp, maxLines = 1)
            }
        }
        if (status != null) {
            if (!status.ok) {
                Text("Hiba: ${status.error}", color = DangerColor, fontSize = 10.sp)
            } else {
                MinerLine("Hashrate", listOfNotNull(status.hashrate5s?.let { "5s: $it" }, status.hashrateAvg?.let { "átlag: $it" }).joinToString(" · ").ifBlank { "?" })
                status.elapsedSec?.let { MinerLine("Üzemidő", formatUptime(it)) }
                if (status.accepted != null || status.rejected != null) {
                    MinerLine("Share", "elfogadott ${status.accepted ?: "?"} / elutasított ${status.rejected ?: "?"}" + (status.hwErrors?.let { " · HW-hiba $it" } ?: ""))
                }
                if (status.temps.isNotEmpty()) {
                    val max = status.temps.maxOrNull() ?: 0.0
                    MinerLine("Hőfok", "max ${"%.0f".format(max)}°C (${status.temps.size} érzékelő)", if (max >= 85) DangerColor else if (max >= 75) WarnColor else TextMain)
                }
                if (status.fans.isNotEmpty()) {
                    val zero = status.fans.any { it < 500 }
                    MinerLine("Ventilátor", status.fans.joinToString(" / ") + " rpm", if (zero) WarnColor else TextMain)
                }
                status.pools.forEachIndexed { i, p ->
                    val alive = p.status.equals("Alive", true)
                    MinerLine("Pool ${i + 1}", "${p.url} · ${p.user} · ${p.status}", if (alive) TextMain else WarnColor)
                }
                status.firmware?.let { MinerLine("Firmware", it) }
            }
            Text("Lekérdezve: ${formatDateTime(status.queriedMs)}", color = TextDim, fontSize = 8.sp)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("LEKÉRDEZÉS", enabled = !querying, filled = true) { onQuery(listOf(ip)) }
            PillButton("WEB-FELÜLET", enabled = true, filled = false) { onOpenWeb("http://$ip/") }
        }
    }
}

@Composable
private fun MinerLine(label: String, value: String, color: androidx.compose.ui.graphics.Color = TextMain) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("$label:", color = TextDim, fontSize = 10.sp, modifier = Modifier.width(78.dp))
        Text(value, color = color, fontSize = 10.sp)
    }
}

private fun formatUptime(sec: Long): String {
    val d = sec / 86_400
    val h = (sec % 86_400) / 3600
    val m = (sec % 3600) / 60
    return if (d > 0) "$d nap $h óra" else if (h > 0) "$h óra $m perc" else "$m perc"
}
