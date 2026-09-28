// Verzio: v0.1.0 - 2026-09-28
package hu.lordathis.networktools.tor

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executor

/**
 * Tor-támogatás az Orbot app MELLÉ telepítve (az app mérete nem nő): az Orbot futtatja a Tor-klienst,
 * mi a helyi proxyját használjuk.
 *  - HTTP-proxy: 127.0.0.1:8118 (a Webolvasó és a tesztek ezt használják - a .onion címeket is a Tor oldja fel);
 *  - SOCKS5: 127.0.0.1:9050.
 */
object Orbot {
    const val PACKAGE = "org.torproject.android"
    const val HTTP_PROXY_PORT = 8118
    const val SOCKS_PORT = 9050
    private const val ACTION_START = "org.torproject.android.intent.action.START"
    private const val EXTRA_PACKAGE = "org.torproject.android.intent.extra.PACKAGE_NAME"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Megkéri az Orbotot, hogy induljon el (ha telepítve van); a Tor felépülése néhány másodperc. */
    fun requestStart(context: Context) {
        try {
            val intent = Intent(ACTION_START).setPackage(PACKAGE).putExtra(EXTRA_PACKAGE, context.packageName)
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            // ha nem sikerül, a felhasználó kézzel indítja
        }
    }

    fun openApp(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE)
        if (launch != null) {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            openStore(context)
        }
    }

    fun openStore(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (e: Exception) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Fut-e az Orbot helyi proxyja (TCP-kapcsolódás a 127.0.0.1:[port]-ra). */
    fun proxyReachable(port: Int = HTTP_PROXY_PORT): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 700) }
        true
    } catch (e: Exception) {
        false
    }

    val httpProxy: Proxy get() = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", HTTP_PROXY_PORT))
}

/**
 * A Webolvasó Tor-módja: a WebView forgalma az Orbot HTTP-proxyján megy. A HELYI címek (192.168.x.x,
 * 10.x, 172.16-31.x, 169.254.x, localhost) CSAK erre az időre kerülnek a proxy alól kivételbe - Tor-mód
 * kikapcsolásakor a proxy-felülírás teljesen megszűnik.
 */
object TorWebProxy {
    fun isSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    fun enable(executor: Executor, onDone: () -> Unit) {
        val builder = ProxyConfig.Builder()
            .addProxyRule("127.0.0.1:${Orbot.HTTP_PROXY_PORT}")
            .addBypassRule("127.0.0.1")
            .addBypassRule("localhost")
            .addBypassRule("10.*")
            .addBypassRule("192.168.*")
            .addBypassRule("169.254.*")
        for (b in 16..31) builder.addBypassRule("172.$b.*")
        ProxyController.getInstance().setProxyOverride(builder.build(), executor, Runnable { onDone() })
    }

    fun disable(executor: Executor, onDone: () -> Unit) {
        ProxyController.getInstance().clearProxyOverride(executor, Runnable { onDone() })
    }
}

/** Egy Tor-on (vagy közvetlenül) végzett HTTP-lekérés eredménye. */
data class TorFetch(val ok: Boolean, val code: Int?, val millis: Long, val body: String?, val error: String?)

object TorProbe {
    /** A Tor Project saját ellenőrző végpontja: {"IsTor":true,"IP":"..."}. */
    const val CHECK_URL = "https://check.torproject.org/api/ip"

    /** A torproject.org hivatalos onion-címe (v3) - a .onion elérés teszteléséhez. */
    const val ONION_TEST_URL = "http://2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion/"

    suspend fun fetch(url: String, viaTor: Boolean, timeoutMs: Int = 30_000, maxBody: Int = 4_000): TorFetch =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            try {
                val u = URL(url)
                val conn = (if (viaTor) u.openConnection(Orbot.httpProxy) else u.openConnection()) as HttpURLConnection
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "NetworkTools-TorTest")
                try {
                    val code = conn.responseCode
                    val stream = if (code in 200..399) conn.inputStream else conn.errorStream
                    val body = stream?.bufferedReader()?.use { r ->
                        val buf = CharArray(maxBody)
                        val n = r.read(buf)
                        if (n > 0) String(buf, 0, n) else ""
                    }
                    TorFetch(code in 200..399, code, System.currentTimeMillis() - start, body, null)
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                TorFetch(false, null, System.currentTimeMillis() - start, null, e.message ?: e.javaClass.simpleName)
            }
        }

    /** A check.torproject.org válaszából: (IsTor, IP). */
    fun parseCheck(body: String?): Pair<Boolean, String?> = try {
        val o = JSONObject(body ?: "")
        o.optBoolean("IsTor", false) to o.optString("IP").ifBlank { null }
    } catch (e: Exception) {
        false to null
    }
}
