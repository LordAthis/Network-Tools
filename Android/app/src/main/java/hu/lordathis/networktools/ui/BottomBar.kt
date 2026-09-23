// Verzio: v0.5.0 - 2026-09-22
package hu.lordathis.networktools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import hu.lordathis.networktools.network.ConnectionKind

/**
 * Alsó ikonsor, minden képernyőn: [mobilnet + fogaskerék] --- [közép: házikó vagy nyíl] --- [WiFi + fogaskerék].
 *
 *  - A mobilnet/WiFi ikonra koppintva NEM a rádiót kapcsolja ki (azt Android 10+ nem engedi appnak) -
 *    az app SAJÁT forgalmát kényszeríti arra a hálózatra (lásd [hu.lordathis.networktools.network.NetworkForcer]).
 *    Az aktív kényszerítés az ikon kiemelésével (Accent szín) látszik.
 *  - A mellette lévő kis fogaskerék a rendszer WiFi/mobiladat beállítását nyitja meg (a tényleges rádió-kapcsoláshoz).
 *  - A közép ikon: a Kezdőlapon lefelé mutató NYÍL (csak akkor aktív/koppintható, ha van megnézhető
 *    eredmény - [hasResults]), minden más képernyőn HÁZIKÓ (vissza a Kezdőlapra).
 */
@Composable
internal fun BottomBar(
    isHome: Boolean,
    hasResults: Boolean,
    forcedTransport: ConnectionKind?,
    wifiActive: Boolean,
    mobileActive: Boolean,
    onToggleWifi: () -> Unit,
    onToggleMobile: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenMobileSettings: () -> Unit,
    onCenterClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .background(DrawerBg)
            .navigationBarsPadding()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportGroup(
            icon = if (mobileActive) Icons.Filled.SignalCellularAlt else Icons.Filled.SignalCellularOff,
            forced = forcedTransport == ConnectionKind.CELLULAR,
            contentDescription = "Mobilnet",
            onToggle = onToggleMobile,
            onSettings = onOpenMobileSettings,
        )

        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .clickable(enabled = isHome.not() || hasResults, onClick = onCenterClick),
            contentAlignment = Alignment.Center,
        ) {
            if (isHome) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Eredmények",
                    tint = if (hasResults) Accent else TextDim,
                    modifier = Modifier.size(36.dp),
                )
            } else {
                HomeButtonIcon(modifier = Modifier.size(44.dp))
            }
        }

        TransportGroup(
            icon = if (wifiActive) Icons.Filled.Wifi else Icons.Filled.WifiOff,
            forced = forcedTransport == ConnectionKind.WIFI,
            contentDescription = "WiFi",
            onToggle = onToggleWifi,
            onSettings = onOpenWifiSettings,
        )
    }
}

@Composable
private fun TransportGroup(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    forced: Boolean,
    contentDescription: String,
    onToggle: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (forced) Accent.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = if (forced) Accent else TextMain, modifier = Modifier.size(24.dp))
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onSettings),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "$contentDescription rendszerbeállítás", tint = TextDim, modifier = Modifier.size(16.dp))
        }
    }
}
