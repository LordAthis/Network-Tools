// Verzio: v0.1.0 - 2026-09-28
package hu.lordathis.networktools.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.lordathis.networktools.engine.AddressEntry

// ---------------------------------------------------------------------------
// Eszközök panel (v0.1.13) - jobb fiók, a Beállítások alatt (router ikon).
//  - Felül, középre igazítva GYORSGOMBOK a leggyakoribb router/átjáró-címekre (192.168.0-2.1) és a
//    ...255-ös végű címekre. A gombon csak a cím VÉGE látszik (…0.1); koppintásra a Beállítások >
//    Általános > Linkek kezelése szerint nyílik (belső Webolvasó / külső böngésző / kérdez) - a
//    LocalOpenLink megnyitóján át, "http://192.168.x.y/" címmel.
//  - Alattuk a JELENLEGI hálózat elmentett eszközei (a tesztekből összesített adatokkal),
//    kattintható címekkel (ugyanaz a sor-megjelenés, mint a Webolvasó LISTA nézetében).
// ---------------------------------------------------------------------------

/** A gyorsgombok: (harmadik oktett, utolsó oktett) - a megjelenítés sorrendjében, soronként. */
private val QUICK_ROWS: List<List<Pair<Int, Int>>> = listOf(
    listOf(0 to 1, 1 to 1, 2 to 1),
    listOf(0 to 255, 1 to 255, 2 to 255),
)

@Composable
internal fun DevicesStripedPanel(
    modifier: Modifier,
    loadAddresses: suspend (currentOnly: Boolean) -> List<AddressEntry>,
    onLog: (String) -> Unit = {},
) {
    val open = LocalOpenLink.current
    var entries by remember { mutableStateOf<List<AddressEntry>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        entries = null
        entries = loadAddresses(true).filter { it.kind == AddressEntry.Kind.LAN }
    }

    StripedPanel(
        modifier = modifier,
        topStripe = StripeSpec(label = "FRISSÍTÉS", enabled = entries != null, onClick = { reload++ }),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("ESZKÖZÖK", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(
                "Gyorsgombok: 192.168.x.y (a gombon csak a cím vége látszik). A megnyitás módját a Beállítások > " +
                    "Általános > Linkek kezelése dönti el.",
                color = TextDim, fontSize = 9.sp,
            )
            for (row in QUICK_ROWS) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for ((third, last) in row) {
                        QuickAddressButton(label = "…$third.$last") {
                            val url = "http://192.168.$third.$last/"
                            onLog("Eszközök: gyorsgomb $url")
                            open(url)
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "JELENLEGI HÁLÓZAT - ELMENTETT ESZKÖZÖK",
                color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            )
            val list = entries
            when {
                list == null -> Text("Betöltés...", color = TextDim, fontSize = 10.sp)
                list.isEmpty() -> Text(
                    "Ezen a hálózaton még nincs elmentett eszköz. A bal fiók tesztjei (pl. Ping-sweep) töltik fel a listát.",
                    color = TextDim, fontSize = 10.sp,
                )
                else -> {
                    list.firstOrNull()?.networkName?.let {
                        Text(it, color = AccentBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    list.forEach { e ->
                        AddressRow(e, showDelete = false, onOpen = { open(e.openUrl) }, onDelete = {})
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun QuickAddressButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(width = 76.dp, height = 36.dp)
            .clip(shape)
            .background(ButtonBg)
            .border(1.dp, Accent.copy(alpha = 0.6f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
