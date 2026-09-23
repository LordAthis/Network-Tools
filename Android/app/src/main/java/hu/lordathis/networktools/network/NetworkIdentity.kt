// Verzio: v0.5.0 - 2026-09-22
package hu.lordathis.networktools.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import java.net.InetAddress

/** Egy adott hálózat típusa (lehet egyszerre több is aktív - "vegyes"). */
enum class ConnectionKind { WIFI, CELLULAR, ETHERNET, VPN, OTHER }

/** Egy aktív hálózati kapcsolat pillanatképe. */
data class ActiveConnection(
    val kind: ConnectionKind,
    val networkHandle: Network,
    val linkSpeedMbps: Int? = null,
    val isMetered: Boolean = false,
)

/**
 * Wi-Fi-azonosító: SSID + BSSID (az AP RÁDIÓJÁNAK saját MAC-címe).
 *
 * FONTOS: ez NEM a LAN-átjáró MAC-je (amit egy ARP-táblából lehetne kiolvasni - lásd [ArpProbe],
 * ami valószínűleg nem elérhető root nélkül). A BSSID egy hivatalos, dokumentált Android API-n
 * keresztül érhető el, és a gyakorlatban ugyanazt a célt szolgálja: egyértelműen megkülönbözteti
 * két, azonos SSID-vel sugárzó, de FIZIKAILAG KÜLÖNBÖZŐ hálózatot egymástól.
 */
data class WifiIdentity(val ssid: String?, val bssid: String?)

/**
 * A jelenlegi hálózati állapot lekérdezése. Semmit nem ír/módosít, csak olvas - biztonságosan
 * hívható bármikor (pl. induláskori gyorsellenőrzéshez, vagy a lenti ikon-sor frissítéséhez).
 */
class NetworkIdentity(private val context: Context) {

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    /**
     * Az összes JELENLEG validált (internetet is adó) hálózat - lehet egyszerre WiFi ÉS mobilnet is ("vegyes").
     *
     * A `ConnectivityManager.getAllNetworks()` Android 12 óta elavultnak jelölt API - a hivatalos javaslat
     * folyamatos `registerNetworkCallback`-alapú követés lenne. Az AppHub MÁR regisztrál egy NetworkCallback-et
     * (hálózatváltás-figyeléshez), de ez a függvény egy EGYSZERI, szinkron pillanatképet ad (induláskor, gyors
     * ellenőrzéshez) - ehhez az `allNetworks` egyszerűbb és elég, a figyelmeztetést tudatosan elfogadjuk.
     */
    @Suppress("DEPRECATION")
    fun activeConnections(): List<ActiveConnection> {
        val result = ArrayList<ActiveConnection>()
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) continue
            val kind = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionKind.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionKind.CELLULAR
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionKind.ETHERNET
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> ConnectionKind.VPN
                else -> ConnectionKind.OTHER
            }
            result.add(
                ActiveConnection(
                    kind = kind,
                    networkHandle = network,
                    linkSpeedMbps = caps.linkDownstreamBandwidthKbps.takeIf { it > 0 }?.let { it / 1000 },
                    isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                )
            )
        }
        return result
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * SSID + BSSID a jelenlegi Wi-Fi kapcsolathoz. Helyhozzáférés (ACCESS_FINE_LOCATION) NÉLKÜL
     * Android ezt "<unknown ssid>" / "02:00:00:00:00:00" placeholderrel adja vissza - ilyenkor
     * [WifiIdentity] mezői null-ok lesznek, és a hívó ezt jelezze a felhasználónak.
     */
    @Suppress("DEPRECATION")
    fun currentWifiIdentity(): WifiIdentity? {
        val wm = wifiManager ?: return null
        if (!hasLocationPermission()) return null
        val info = wm.connectionInfo ?: return null
        val ssidRaw = info.ssid?.trim('"')
        val bssidRaw = info.bssid
        val ssid = ssidRaw?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
        val bssid = bssidRaw?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }
        if (ssid == null && bssid == null) return null
        return WifiIdentity(ssid, bssid)
    }

    /** Az alapértelmezett átjáró IP-je egy adott hálózaton (a route-okból, nem ARP-ból). */
    fun gatewayAddress(network: Network): InetAddress? {
        val lp: LinkProperties = cm.getLinkProperties(network) ?: return null
        return lp.routes.firstOrNull { it.isDefaultRoute }?.gateway
    }

    /** A hálózat saját (a telefonnak kiosztott) IPv4/IPv6 címei. */
    fun localAddresses(network: Network): List<InetAddress> {
        val lp: LinkProperties = cm.getLinkProperties(network) ?: return emptyList()
        return lp.linkAddresses.map { it.address }
    }

    fun dnsServers(network: Network): List<InetAddress> {
        val lp: LinkProperties = cm.getLinkProperties(network) ?: return emptyList()
        return lp.dnsServers
    }

    /**
     * Egy IPv4 cím /24-es alhálójának összes host-címe (pl. 192.168.1.0/24 -> .1 .. .254).
     * Csak akkor van értelme, ha [address] valódi IPv4.
     */
    fun subnetHosts(address: InetAddress): List<String> {
        val parts = address.hostAddress?.split(".") ?: return emptyList()
        if (parts.size != 4) return emptyList()
        val prefix = "${parts[0]}.${parts[1]}.${parts[2]}."
        return (1..254).map { prefix + it }
    }
}
