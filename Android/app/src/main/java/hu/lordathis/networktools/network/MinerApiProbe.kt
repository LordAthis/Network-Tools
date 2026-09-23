// Verzio: v0.5.0 - 2026-09-22
package hu.lordathis.networktools.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import org.json.JSONObject

/** cgminer/bmminer (és leszármazottaik, pl. Whatsminer) JSON-over-TCP API próbája - a MinerStatus.ps1 portja. */
object MinerApiProbe {

    data class MinerApiResult(val port: Int, val command: String, val rawResponse: String, val json: JSONObject?)

    /** Portok, amiken ez a protokoll tipikusan fut. */
    val PORTS = listOf(4028, 4433)

    /**
     * Egy parancs elküldése (pl. "version", "summary", "pools") és a válasz beolvasása.
     * A protokoll: nyers TCP, egyetlen JSON kérés, a kapcsolat a válasz után lezárul.
     */
    suspend fun query(host: String, port: Int, command: String, timeoutMs: Int = 1500): MinerApiResult? =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    socket.soTimeout = timeoutMs
                    val payload = JSONObject().put("command", command).toString()
                    socket.getOutputStream().write(payload.toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()
                    val raw = socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
                    // A cgminer-család válasza gyakran egy záró NUL byte-ot is küld - a JSON-parsernek ez nem kell.
                    val cleaned = raw.trim('\u0000', '\n', '\r', ' ')
                    val json = try {
                        JSONObject(cleaned)
                    } catch (e: Exception) {
                        null
                    }
                    if (cleaned.isEmpty()) null else MinerApiResult(port, command, cleaned, json)
                }
            } catch (e: Exception) {
                null
            }
        }

    /** Gyors "van-e itt cgminer-szerű API" teszt: a "version" parancsra kapunk-e bármilyen JSON-t. */
    suspend fun probe(host: String): MinerApiResult? {
        for (port in PORTS) {
            query(host, port, "version")?.let { return it }
        }
        return null
    }
}
