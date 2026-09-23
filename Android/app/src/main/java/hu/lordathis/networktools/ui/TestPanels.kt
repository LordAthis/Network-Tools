// Verzio: v0.5.0 - 2026-09-22
package hu.lordathis.networktools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.lordathis.networktools.engine.JobStatus
import hu.lordathis.networktools.engine.TestDef
import hu.lordathis.networktools.engine.TestGroup
import hu.lordathis.networktools.engine.TestJob

/**
 * F1/F2/F3 (és a jövőbeli M1/M2/M3) csoport-panel: a csoport tesztjeinek listája, mindegyik saját
 * FUTTATÁS gombbal + állapot-jelvénnyel. Az élő, terminál-szerű kimenetet a Kezdőlap VISSZAJELZÉSEK
 * panelje mutatja (ott, lapfüllel, ha egyszerre több fut) - ez a panel csak a listát és az indítást adja.
 */
@Composable
internal fun TestGroupStripedPanel(
    modifier: Modifier,
    group: TestGroup,
    jobs: List<TestJob>,
    onRun: (TestDef) -> Unit,
) {
    StripedPanel(
        modifier = modifier,
        topStripe = StripeSpec(group.name.uppercase(), enabled = false, onClick = null),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (test in group.tests) {
                val latestJob = jobs.filter { it.testId == test.id }.maxByOrNull { it.startedMs }
                TestRow(test, latestJob, onRun = { onRun(test) })
            }
        }
    }
}

@Composable
private fun TestRow(test: TestDef, job: TestJob?, onRun: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val running = job?.status == JobStatus.RUNNING
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Accent.copy(alpha = 0.4f), shape)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(test.name, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(test.shortCode, color = TextDim, fontSize = 9.sp)
            }
            when {
                running -> StatusChip("FUT", AccentBlue)
                job?.status == JobStatus.DONE -> StatusChip("KÉSZ", Accent)
                job?.status == JobStatus.FAILED -> StatusChip("HIBA", DangerColor)
            }
            Spacer(Modifier.width(8.dp))
            PillButton("FUTTATÁS", enabled = !running, filled = true, onClick = onRun)
        }
        val headline = job?.lines?.lastOrNull()
        if (headline != null) {
            Spacer(Modifier.height(4.dp))
            Text(headline, color = TextDim, fontSize = 9.sp, maxLines = 2)
        }
    }
}

/** Egyszerű, még funkció nélküli panel ("kidolgozás alatt") - Sebességteszt, Külső szolgáltatók. */
@Composable
internal fun WorkInProgressStripedPanel(modifier: Modifier, title: String) {
    StripedPanel(modifier = modifier, topStripe = StripeSpec(title, enabled = false, onClick = null)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("KIDOLGOZÁS ALATT", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
}
