// Verzio: v0.5.0 - 2026-09-22
package hu.lordathis.networktools.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Egy web-felület címének (<title>) lekérdezése - a MinerStatus.ps1 web-UI próbája. */
object HttpTitleProbe {
    private val TITLE_REGEX = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE)

    suspend fun fetchTitle(host: String, port: Int, timeoutMs: Int = 1500): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("http://$host:$port/")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "NetworkTools/1.0")
            conn.inputStream.use { stream ->
                val text = stream.bufferedReader(Charsets.UTF_8).readText().take(8192)
                TITLE_REGEX.find(text)?.groupValues?.get(1)?.trim()
            }
        } catch (e: Exception) {
            null
        }
    }
}
