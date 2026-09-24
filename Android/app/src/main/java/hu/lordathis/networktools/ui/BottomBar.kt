// Verzio: v0.6.0 - 2026-09-24
package hu.lordathis.networktools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import hu.lordathis.networktools.network.ConnectionKind

/**
 * Alsó ikonsor, minden képernyőn: [mobilnet] --- [közép: házikó vagy fejjel lefelé házikó] --- [WiFi].
 *
 * A mobilnet/WiFi ikonra koppintva a rendszer WiFi/mobiladat beállítását nyitja meg - NINCS mellette
 * külön fogaskerék (az korábban tévesen ide került; a "forgalom kényszerítése erre a hálózatra"
 * kapcsoló a Beállítások > Hálózati beállítások alatt van, nem itt). Az ikon színe csak JELZI, ha
 * épp aktív a kényszerítés (lásd [forcedTransport]) - a tényleges be/kikapcsolás a Beállításokban van.
 *
 * A közép ikon MINDIG ugyanaz a házikó-ikon, ugyanabban a méretben: a Kezdőlapon fejjel LEFELÉ (csak
 * akkor koppintható, ha van megnézhető eredmény - [hasResults] - és az Eredmények képernyőre visz),
 * minden más képernyőn a megszokott állásban (vissza a Kezdőlapra).
 *
 * A három ikon egy Box-ban, explicit Alignment.CenterStart / Center / CenterEnd pozícióval van
 * elhelyezve - ez garantálja, hogy a közép ikon TÉNYLEG középen legyen, függetlenül attól, hogy a két
 * szélső ikon mekkora (a korábbi Row+SpaceEvenly ezt csak akkor adta volna, ha minden elem egyforma
 * széles - emiatt csúszott el korábban a házikó).
 */
@Composable
internal fun BottomBar(
    isHome: Boolean,
    hasResults: Boolean,
    forcedTransport: ConnectionKind?,
    wifiActive: Boolean,
    mobileActive: Boolean,
    onOpenWifiSettings: () -> Unit,
    onOpenMobileSettings: () -> Unit,
    onCenterClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(DrawerBg)
            .navigationBarsPadding()
            .height(72.dp)
            .padding(horizontal = 20.dp),
    ) {
        TransportIcon(
            icon = if (mobileActive) Icons.Filled.SignalCellularAlt else Icons.Filled.SignalCellularOff,
            forced = forcedTransport == ConnectionKind.CELLULAR,
            contentDescription = "Mobilnet beállítás",
            onClick = onOpenMobileSettings,
            modifier = Modifier.align(Alignment.CenterStart),
        )

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(56.dp)
                .clip(CircleShape)
                .clickable(enabled = isHome.not() || hasResults, onClick = onCenterClick),
            contentAlignment = Alignment.Center,
        ) {
            HomeButtonIcon(
                modifier = Modifier
                    .size(48.dp)
                    .rotate(if (isHome) 180f else 0f),
                color = if (isHome && !hasResults) TextDim else Accent,
            )
        }

        TransportIcon(
            icon = if (wifiActive) Icons.Filled.Wifi else Icons.Filled.WifiOff,
            forced = forcedTransport == ConnectionKind.WIFI,
            contentDescription = "WiFi beállítás",
            onClick = onOpenWifiSettings,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun TransportIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    forced: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (forced) Accent.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = if (forced) Accent else TextMain, modifier = Modifier.size(26.dp))
    }
}
