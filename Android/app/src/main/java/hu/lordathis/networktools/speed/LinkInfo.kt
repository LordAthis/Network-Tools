// Verzio: v0.1.0 - 2026-09-27
package hu.lordathis.networktools.speed

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import hu.lordathis.networktools.network.ConnectionKind
import hu.lordathis.networktools.network.NetworkIdentity
import java.io.File
import java.net.Inet4Address

/**
 * A jelenlegi kapcsolat "papíron" ismert sebesség-adatai: WiFi PHY-sebesség (rx/tx), sáv, szabvány,
 * jelerősség; vezetékesnél a link-sebesség, ha a rendszer engedi olvasni (/sys/class/net/<if>/speed -
 * sok eszközön SELinux miatt NEM olvasható, ilyenkor null).
 */
object LinkInfo {

    fun snapshot(context: Context, identity: NetworkIdentity): LinkSnapshot {
        val conns = identity.activeConnections()
        val main = conns.firstOrNull { it.kind == ConnectionKind.ETHERNET }
            ?: conns.firstOrNull { it.kind == ConnectionKind.WIFI }
            ?: conns.firstOrNull()
        if (main == null) return LinkSnapshot(takenMs = System.currentTimeMillis())

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(main.networkHandle)
        val gateway = identity.gatewayAddress(main.networkHandle)?.hostAddress
        val dns = identity.dnsServers(main.networkHandle).mapNotNull { it.hostAddress }
        val local = identity.localAddresses(main.networkHandle).firstOrNull { it is Inet4Address }?.hostAddress

        var snap = LinkSnapshot(
            kind = main.kind,
            estimatedDownMbps = main.linkSpeedMbps,
            gateway = gateway,
            dnsServers = dns,
            localIpv4 = local,
            takenMs = System.currentTimeMillis(),
        )

        if (main.kind == ConnectionKind.WIFI) {
            val info = wifiInfo(context)
            if (info != null) {
                snap = snap.copy(
                    wifiLinkMbps = info.linkSpeed.takeIf { it > 0 },
                    wifiRxMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.rxLinkSpeedMbps.takeIf { it > 0 } else null,
                    wifiTxMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.txLinkSpeedMbps.takeIf { it > 0 } else null,
                    wifiMaxRxMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) info.maxSupportedRxLinkSpeedMbps.takeIf { it > 0 } else null,
                    wifiFreqMhz = info.frequency.takeIf { it > 0 },
                    wifiStandard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) standardName(info.wifiStandard) else null,
                    wifiRssi = info.rssi.takeIf { it > -127 && it < 0 },
                )
            }
        }

        if (main.kind == ConnectionKind.ETHERNET) {
            val iface = lp?.interfaceName
            snap = snap.copy(ethernetIface = iface, ethernetMbps = iface?.let { readEthernetSpeed(it) })
        }
        return snap
    }

    @Suppress("DEPRECATION")
    private fun wifiInfo(context: Context): WifiInfo? = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wm?.connectionInfo
    } catch (e: Exception) {
        null
    }

    private fun standardName(code: Int): String? = when (code) {
        1 -> "802.11a/b/g (legacy)"
        4 -> "WiFi 4 (802.11n)"
        5 -> "WiFi 5 (802.11ac)"
        6 -> "WiFi 6 (802.11ax)"
        7 -> "802.11ad"
        8 -> "WiFi 7 (802.11be)"
        else -> null
    }

    /** /sys/class/net/<iface>/speed - Mbps; sok Android-eszközön nem olvasható (akkor null). */
    private fun readEthernetSpeed(iface: String): Int? = try {
        File("/sys/class/net/$iface/speed").readText().trim().toIntOrNull()?.takeIf { it > 0 }
    } catch (e: Exception) {
        null
    }

    /** Magánhálózati (LAN) IPv4 cím-e. */
    fun isPrivateIpv4(ip: String): Boolean {
        val p = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) ||
            (p[0] == 169 && p[1] == 254)
    }
}
