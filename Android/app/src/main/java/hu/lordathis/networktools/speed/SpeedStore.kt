// Verzio: v0.1.0 - 2026-09-27
package hu.lordathis.networktools.speed

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A Sebességteszt tartós adatai a profiles/ mappában (így az Adatmentés része):
 *  - `speedtests.json`: hálózatonként az előfizetett sebesség + a korábbi mérések előzménye
 *    (ehhez hasonlítjuk az új méréseket: "a szokásoshoz képest");
 *  - `lan_hosts.json`: hálózatonként a korábbi tesztek (ping-sweep, hostname, SNMP, HTTP-cím,
 *    SSH-banner, miner API) által látott LAN-eszközök - ezek a LAN-mérés célpontjai.
 */
class SpeedStore(private val dir: File) {
    private val speedFile = File(dir, "speedtests.json")
    private val hostsFile = File(dir, "lan_hosts.json")

    // ------------------------------------------------------------------ Előfizetés

    @Synchronized
    fun contract(networkKey: String): ContractSpeed? {
        val o = readSpeedRoot().optJSONObject("contracts")?.optJSONObject(networkKey) ?: return null
        val down = if (o.isNull("down")) null else o.optInt("down").takeIf { it > 0 }
        val up = if (o.isNull("up")) null else o.optInt("up").takeIf { it > 0 }
        return if (down == null && up == null) null else ContractSpeed(down, up)
    }

    @Synchronized
    fun setContract(networkKey: String, contract: ContractSpeed?) {
        val root = readSpeedRoot()
        val contracts = root.optJSONObject("contracts") ?: JSONObject()
        if (contract == null || (contract.downMbps == null && contract.upMbps == null)) {
            contracts.remove(networkKey)
        } else {
            contracts.put(networkKey, JSONObject().apply {
                put("down", contract.downMbps ?: JSONObject.NULL)
                put("up", contract.upMbps ?: JSONObject.NULL)
            })
        }
        root.put("contracts", contracts)
        write(speedFile, root)
    }

    // ------------------------------------------------------------------ Előzmények

    @Synchronized
    fun history(networkKey: String? = null): List<SpeedHistoryEntry> {
        val arr = readSpeedRoot().optJSONArray("history") ?: JSONArray()
        val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> decodeHistory(o) } }
        return if (networkKey == null) all else all.filter { it.networkKey == networkKey }
    }

    @Synchronized
    fun addHistory(entry: SpeedHistoryEntry) {
        val root = readSpeedRoot()
        val arr = root.optJSONArray("history") ?: JSONArray()
        val list = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } + encodeHistory(entry)
        val out = JSONArray()
        list.takeLast(MAX_HISTORY).forEach { out.put(it) }
        root.put("history", out)
        write(speedFile, root)
    }

    // ------------------------------------------------------------------ LAN-eszközök

    @Synchronized
    fun lanHosts(networkKey: String): List<LanHostInfo> {
        val arr = readHostsRoot().optJSONObject("networks")?.optJSONArray(networkKey) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> decodeHost(o) } }
    }

    /** Új megfigyelések összefésülése a meglévőkkel (IP-cím szerint; az új, nem üres mező nyer). */
    @Synchronized
    fun mergeLanHosts(networkKey: String, observed: List<LanHostInfo>) {
        if (observed.isEmpty()) return
        val root = readHostsRoot()
        val networks = root.optJSONObject("networks") ?: JSONObject()
        val existing = lanHosts(networkKey).associateBy { it.ip }.toMutableMap()
        for (h in observed) {
            val prev = existing[h.ip]
            existing[h.ip] = prev?.mergedWith(h) ?: h
        }
        val arr = JSONArray()
        existing.values
            .sortedByDescending { it.lastSeenMs }
            .take(MAX_HOSTS_PER_NETWORK)
            .sortedBy { ipSortKey(it.ip) }
            .forEach { arr.put(encodeHost(it)) }
        networks.put(networkKey, arr)
        root.put("networks", networks)
        write(hostsFile, root)
    }

    // ------------------------------------------------------------------ JSON segédek

    private fun readSpeedRoot(): JSONObject = read(speedFile)
    private fun readHostsRoot(): JSONObject = read(hostsFile)

    private fun read(file: File): JSONObject = try {
        if (file.exists()) JSONObject(file.readText(Charsets.UTF_8)) else JSONObject().put("version", 1)
    } catch (e: Exception) {
        JSONObject().put("version", 1)
    }

    private fun write(file: File, root: JSONObject) {
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(root.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(root.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun ipSortKey(ip: String): Long =
        ip.split(".").mapNotNull { it.toLongOrNull() }.fold(0L) { acc, p -> acc * 256 + p }

    private fun optDouble(o: JSONObject, key: String): Double? =
        if (o.isNull(key)) null else o.optDouble(key).takeIf { !it.isNaN() }

    private fun optIntOrNull(o: JSONObject, key: String): Int? = if (o.isNull(key)) null else o.optInt(key)

    private fun optStr(o: JSONObject, key: String): String? = if (o.isNull(key)) null else o.optString(key).ifBlank { null }

    private fun encodeHistory(e: SpeedHistoryEntry): JSONObject = JSONObject().apply {
        put("networkKey", e.networkKey)
        put("timeMs", e.timeMs)
        put("type", e.type)
        put("connKind", e.connKind)
        put("server", e.server)
        put("downMbps", e.downMbps ?: JSONObject.NULL)
        put("upMbps", e.upMbps ?: JSONObject.NULL)
        put("idleLatencyMs", e.idleLatencyMs ?: JSONObject.NULL)
        put("loadedLatencyMs", e.loadedLatencyMs ?: JSONObject.NULL)
        put("lossPct", e.lossPct ?: JSONObject.NULL)
        put("jitterMs", e.jitterMs ?: JSONObject.NULL)
        put("rttAvgMs", e.rttAvgMs ?: JSONObject.NULL)
        put("linkMbps", e.linkMbps ?: JSONObject.NULL)
        put("headline", e.headline)
    }

    private fun decodeHistory(o: JSONObject) = SpeedHistoryEntry(
        networkKey = o.optString("networkKey"),
        timeMs = o.optLong("timeMs"),
        type = o.optString("type"),
        connKind = o.optString("connKind"),
        server = o.optString("server"),
        downMbps = optDouble(o, "downMbps"),
        upMbps = optDouble(o, "upMbps"),
        idleLatencyMs = optDouble(o, "idleLatencyMs"),
        loadedLatencyMs = optDouble(o, "loadedLatencyMs"),
        lossPct = optDouble(o, "lossPct"),
        jitterMs = optDouble(o, "jitterMs"),
        rttAvgMs = optDouble(o, "rttAvgMs"),
        linkMbps = optIntOrNull(o, "linkMbps"),
        headline = o.optString("headline"),
    )

    private fun encodeHost(h: LanHostInfo): JSONObject = JSONObject().apply {
        put("ip", h.ip)
        put("hostname", h.hostname ?: JSONObject.NULL)
        put("httpTitle", h.httpTitle ?: JSONObject.NULL)
        put("snmpDescr", h.snmpDescr ?: JSONObject.NULL)
        put("sshBanner", h.sshBanner ?: JSONObject.NULL)
        put("minerInfo", h.minerInfo ?: JSONObject.NULL)
        put("lastSeenMs", h.lastSeenMs)
    }

    private fun decodeHost(o: JSONObject) = LanHostInfo(
        ip = o.optString("ip"),
        hostname = optStr(o, "hostname"),
        httpTitle = optStr(o, "httpTitle"),
        snmpDescr = optStr(o, "snmpDescr"),
        sshBanner = optStr(o, "sshBanner"),
        minerInfo = optStr(o, "minerInfo"),
        lastSeenMs = o.optLong("lastSeenMs"),
    )

    private companion object {
        const val MAX_HISTORY = 400
        const val MAX_HOSTS_PER_NETWORK = 254
    }
}
