// Verzio: v0.5.0 - 2026-09-22
package hu.lordathis.networktools.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * "Kényszerítés erre a hálózatra" - a házikó melletti WiFi/mobilnet ikonok mögötti logika.
 *
 * FONTOS, amit ez NEM csinál: Android 10 óta egy telepített app NEM tudja ténylegesen ki/bekapcsolni
 * a WiFi vagy mobilnet RÁDIÓT (az `WifiManager.setWifiEnabled()` app-ból hatástalan API-hívás lett).
 * Amit VISZONT lehet, és a cél szempontjából (,,egy teszt csak egy hálózaton menjen, a másik ne
 * zavarjon bele'') ugyanazt éri el: a `bindProcessToNetwork`-kel az app SAJÁT forgalma egyetlen,
 * kiválasztott hálózatra kényszeríthető - a másik rádió eközben fizikailag bekapcsolva marad, csak
 * ez az app nem használja.
 */
object NetworkForcer {

    /** Erre a hálózat-típusra kényszeríti az app forgalmát; null = nincs kényszerítés (rendszer-alapértelmezett). */
    fun apply(context: Context, kind: ConnectionKind?) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (kind == null) {
            cm.bindProcessToNetwork(null)
            return
        }
        val transport = when (kind) {
            ConnectionKind.WIFI -> NetworkCapabilities.TRANSPORT_WIFI
            ConnectionKind.CELLULAR -> NetworkCapabilities.TRANSPORT_CELLULAR
            ConnectionKind.ETHERNET -> NetworkCapabilities.TRANSPORT_ETHERNET
            else -> return
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(transport)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.bindProcessToNetwork(network)
                cm.unregisterNetworkCallback(this)
            }
        }
        try {
            cm.requestNetwork(request, callback)
        } catch (e: Exception) {
            // A kért hálózat-típus nem elérhető - marad a rendszer-alapértelmezett útvonal.
        }
    }

    /** Rendszerbeállítás megnyitása a tényleges rádió ki/bekapcsolásához (a fogaskerék-ikon célja). */
    fun openSystemToggle(context: Context, kind: ConnectionKind) {
        val action = when (kind) {
            ConnectionKind.WIFI -> android.provider.Settings.ACTION_WIFI_SETTINGS
            ConnectionKind.CELLULAR -> android.provider.Settings.ACTION_DATA_ROAMING_SETTINGS
            else -> android.provider.Settings.ACTION_WIRELESS_SETTINGS
        }
        try {
            context.startActivity(android.content.Intent(action).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
