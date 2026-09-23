// Verzio: v0.5.0 - 2026-09-22
package hu.lordathis.networktools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.lordathis.networktools.engine.LogFileInfo
import hu.lordathis.networktools.storage.ExportHistoryEntry

/**
 * MENTÉS panel (a korábbi, Beállításokon belüli "SZINKRONIZÁLÁS MOST" blokk önálló panelként).
 *
 * MEGJEGYZÉS: az eredeti terv (Google Drive-val kétirányú, automatikus szinkron) helyett a végleges
 * döntés a LÁTHATÓ, KÉZI mentés lett (lásd Beállítások > Mentési beállítások korábbi egyeztetés) -
 * ez a panel ennek a kézi mentésnek ad önálló, teljes képernyős helyet: log-doboz + előzménylista +
 * a naplófájlok kezelése egy helyen, gombnyomásra.
 */
@Composable
internal fun SyncStripedPanel(
    modifier: Modifier,
    recentLog: List<String>,
    history: List<ExportHistoryEntry>,
    logFiles: List<LogFileInfo>,
    onExportNow: () -> Unit,
    onDeleteLogFile: (String) -> Unit,
) {
    StripedPanel(
        modifier = modifier,
        topStripe = StripeSpec("MENTÉS MOST", enabled = true, onClick = onExportNow),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text("MENTÉS", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))

            // Log-doboz: kb. a rendelkezésre álló hely 1/3-a, görgethető.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Accent.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .padding(8.dp),
            ) {
                if (recentLog.isEmpty()) {
                    Text("Még nincs mentési eseménynapló.", color = TextDim, fontSize = 10.sp)
                } else {
                    LazyColumn {
                        items(recentLog) { line -> Text(line, color = Accent, fontSize = 9.sp) }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("KORÁBBI MENTÉSEK", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(4.dp))
            Box(modifier = Modifier.weight(2f)) {
                if (history.isEmpty()) {
                    Text("Még nem volt mentés.", color = TextDim, fontSize = 11.sp)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(history) { entry -> HistoryRow(entry) }
                        item {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "NAPLÓFÁJLOK A TELEFONON",
                                color = Accent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                            )
                        }
                        items(logFiles) { file ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(file.name, color = TextMain, fontSize = 10.sp, modifier = Modifier.weight(1f), maxLines = 1)
                                val shape = RoundedCornerShape(8.dp)
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(shape)
                                        .border(1.dp, DangerColor.copy(alpha = 0.6f), shape)
                                        .clickable { onDeleteLogFile(file.name) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Törlés", tint = DangerColor, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: ExportHistoryEntry) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Accent.copy(alpha = 0.35f), shape)
            .padding(8.dp),
    ) {
        Text("${entry.what} -> ${entry.destination}", color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text("${formatDateTime(entry.whenMs)}  ·  ${entry.detail}", color = TextDim, fontSize = 9.sp)
    }
}
