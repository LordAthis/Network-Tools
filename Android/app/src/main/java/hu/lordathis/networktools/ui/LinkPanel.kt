// Verzio: v0.1.0 - 2026-09-28
package hu.lordathis.networktools.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.lordathis.networktools.link.LinkPeer
import hu.lordathis.networktools.link.LinkProtocol
import hu.lordathis.networktools.link.LinkService
import hu.lordathis.networktools.settings.AppPreferences
import kotlinx.coroutines.launch

/**
 * Beállítások > LINKELÉS (előkészítés): ennek a telefonnak a neve/azonosítója, láthatóság (válaszol-e
 * más Network-Tools példányoknak), keresés a helyi hálózaton és a megadott távoli címeken, és UDP
 * visszhang-mérés a megtalált társ felé.
 */
@Composable
internal fun LinkSettingsSection(link: LinkService, prefs: AppPreferences) {
    val peers by link.peers.collectAsState()
    val visible by link.visible.collectAsState()
    var name by remember { mutableStateOf(prefs.linkNodeName) }
    var remote by remember { mutableStateOf(prefs.linkRemoteTargets) }
    var searching by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var pingResult by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(10.dp)

    Column(
        modifier = Modifier.fillMaxWidth().border(1.dp, Accent.copy(alpha = 0.45f), shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("LINKELÉS (ELŐKÉSZÍTÉS)", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(
            "Network-Tools példányok egymásra találása (telefon ↔ telefon most; a Windowsos változat a következő körben): " +
                "bemutatkozás (HELLO) és UDP-visszhang (PING) az ${LinkProtocol.UDP_PORT}-es porton. A helyi hálózaton " +
                "automatikusan (broadcast), távoli gépnél a megadott címen (port-továbbítással vagy VPN-en át).",
            color = TextDim, fontSize = 10.sp,
        )
        Text("Azonosító: ${link.nodeId.take(8)}…", color = TextDim, fontSize = 9.sp)
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                prefs.linkNodeName = it.trim()
            },
            label = { Text("A telefon neve a többieknek (üres = ${link.nodeName})", fontSize = 10.sp) },
            singleLine = true,
            colors = appFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Látható a többi példány számára", color = TextMain, fontSize = 12.sp)
                Text(
                    if (visible) "Válaszol a bemutatkozásra és a pingre (UDP ${LinkProtocol.UDP_PORT})." else "Kikapcsolva - senkinek nem válaszol.",
                    color = TextDim, fontSize = 9.sp,
                )
            }
            Switch(
                checked = visible,
                onCheckedChange = { on -> scope.launch { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { link.setVisible(on) } } },
                colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(alpha = 0.4f)),
            )
        }
        OutlinedTextField(
            value = remote,
            onValueChange = {
                remote = it
                prefs.linkRemoteTargets = it
            },
            label = { Text("Távoli címek (soronként: IP vagy DDNS-név[:port])", fontSize = 10.sp) },
            colors = appFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(if (searching) "KERESÉS..." else "KERESÉS", enabled = !searching, filled = true) {
                searching = true
                status = ""
                scope.launch {
                    val found = link.discover(remote.lines())
                    status = if (found.isEmpty()) "Nem válaszolt más példány." else "${found.size} példány válaszolt."
                    searching = false
                }
            }
            if (peers.isNotEmpty()) PillButton("LISTA TÖRLÉSE", enabled = true, filled = false) { link.forgetPeers() }
        }
        if (status.isNotBlank()) Text(status, color = AccentBlue, fontSize = 10.sp)
        peers.sortedByDescending { it.lastSeenMs }.forEach { p ->
            PeerRow(p, pingResult[p.nodeId]) {
                pingResult = pingResult + (p.nodeId to "mérés...")
                scope.launch {
                    val r = link.ping(p, count = 10)
                    val ok = r.filterNotNull()
                    val lost = r.size - ok.size
                    pingResult = pingResult + (
                        p.nodeId to if (ok.isEmpty()) "nincs válasz ($lost/${r.size} elveszett)"
                        else "RTT min/átl/max ${"%.1f".format(ok.minOrNull())}/${"%.1f".format(ok.average())}/${"%.1f".format(ok.maxOrNull())} ms, veszteség $lost/${r.size}"
                        )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "Biztonság: v1-ben még nincs párosítás/titkosítás - a láthatóság csak a nevet, platformot és verziót árulja el, " +
                "adatot nem. A tervezett párosítókulcsos (HMAC) változat: Android/LINK_PROTOCOL.md.",
            color = TextDim, fontSize = 9.sp,
        )
    }
}

@Composable
private fun PeerRow(p: LinkPeer, result: String?, onPing: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier.fillMaxWidth().border(1.dp, AccentBlue.copy(alpha = 0.45f), shape).padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name.ifBlank { p.nodeId.take(8) }, color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${p.address}:${p.port} · ${p.platform} v${p.app} · ${p.via}" + (p.rttMs?.let { " · ${"%.1f".format(it)} ms" } ?: ""),
                    color = TextDim, fontSize = 9.sp,
                )
            }
            PillButton("PING", enabled = true, filled = false, onClick = onPing)
        }
        if (result != null) Text(result, color = AccentBlue, fontSize = 9.sp)
    }
}
