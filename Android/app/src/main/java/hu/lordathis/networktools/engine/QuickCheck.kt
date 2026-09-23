// Verzio: v0.5.0 - 2026-09-22
package hu.lordathis.networktools.engine

import hu.lordathis.networktools.network.ActiveConnection
import hu.lordathis.networktools.network.NetworkIdentity
import hu.lordathis.networktools.network.PingTools
import hu.lordathis.networktools.network.WifiIdentity
import hu.lordathis.networktools.profiles.NetworkProfile
import hu.lordathis.networktools.profiles.ProfileMatch
import hu.lordathis.networktools.profiles.ProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Induláskori / hálózatváltáskori GYORS ellenőrzés. FONTOS: ez NEM a mély teszt-motor
 * ([TestEngine]) - kevés, olcsó lépés, hogy azonnal megjelenhessen a Kezdőlapon és a
 * Gyorsjelentés panel FELSŐ, állandó keretében. Amíg a hálózat/kapcsolat él, ez a kártya
 * MARAD (és a hálózatváltással frissül) - mély kutatási adat ide sosem kerül, csak ebbe a
 * gyors összegzésbe.
 */
data class QuickCheckState(
    val connections: List<ActiveConnection> = emptyList(),
    val wifiIdentity: WifiIdentity? = null,
    val match: ProfileMatch = ProfileMatch.Unknown,
    val activeProfile: NetworkProfile? = null,
    val quickDeviceCount: Int? = null,
    val internetReachable: Boolean? = null,
    val running: Boolean = false,
    val updatedMs: Long = 0L,
) {
    /** A hálózat kulcsa a [QuickReportStore]-hoz és a LOG-elnevezéshez - lásd [NetworkProfile.logSafeName]. */
    val networkKey: String
        get() = activeProfile?.id ?: (wifiIdentity?.bssid?.let { "unknown:" + it.replace(":", "") } ?: "unknown:none")

    val networkLogName: String
        get() = activeProfile?.logSafeName() ?: run {
            val macSuffix = wifiIdentity?.bssid?.replace(":", "")?.takeLast(6)?.uppercase()
            "Ismeretlen" + (macSuffix?.let { "-$it" } ?: "")
        }
}

class QuickCheckRunner(
    private val identity: NetworkIdentity,
    private val profileStore: ProfileStore,
) {
    /**
     * [existingProfiles] a hívó (AppHub) már betöltött listája - itt nem olvassuk újra a lemezt
     * minden hálózatváltáskor. [deepSweep]: fusson-e egy gyors (rövid timeout-ú) élő-host-számlálás is.
     */
    suspend fun run(existingProfiles: List<NetworkProfile>, deepSweep: Boolean = true): QuickCheckState =
        withContext(Dispatchers.IO) {
            val connections = identity.activeConnections()
            val wifi = identity.currentWifiIdentity()
            val match = profileStore.match(existingProfiles, wifi?.ssid, wifi?.bssid)
            val activeProfile = (match as? ProfileMatch.Exact)?.profile

            var deviceCount: Int? = null
            if (deepSweep) {
                val wifiConn = connections.firstOrNull { it.kind == hu.lordathis.networktools.network.ConnectionKind.WIFI }
                if (wifiConn != null) {
                    val ipv4 = identity.localAddresses(wifiConn.networkHandle).firstOrNull { it is java.net.Inet4Address }
                    if (ipv4 != null) {
                        val hosts = identity.subnetHosts(ipv4)
                        // Gyors, rövid timeout-ú számlálás - NEM a teljes B2 ping-sweep (az a mély F1 teszt dolga).
                        deviceCount = PingTools.sweep(hosts, concurrency = 48, timeoutMs = 200).size
                    }
                }
            }

            val internetOk = connections.isNotEmpty()

            QuickCheckState(
                connections = connections,
                wifiIdentity = wifi,
                match = match,
                activeProfile = activeProfile,
                quickDeviceCount = deviceCount,
                internetReachable = internetOk,
                running = false,
                updatedMs = System.currentTimeMillis(),
            )
        }
}
