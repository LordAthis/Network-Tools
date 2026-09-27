// Verzio: v0.1.0 - 2026-09-27
package hu.lordathis.networktools.speed

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Gyártó-specifikus port-sebesség lista: melyik eszköz (modell/család) milyen névleges hálózati
 * sebességgel dolgozik (pl. Antminer vezérlőpanel: 10/100 Mbps; ESP32-es IoT: csak 2,4 GHz WiFi).
 *
 * Két forrásból áll össze:
 *  1. `assets/device_speed_catalog.json` - a repóban karbantartott, bővülő alaplista;
 *  2. `profiles/device_speeds_user.json` - a SAJÁT bővítések (a Sebességteszt LAN-mérésénél a
 *     "LISTÁBA" gombbal felvett eszközök) + a saját mérések automatikus rögzítése ("measured").
 *     A profiles/ mappa része az Adatmentésnek, így újratelepítés után is megmarad.
 *
 * Egyeztetés: az eszköz ujjlenyomatában (hostname, HTTP-cím, SNMP sysDescr, SSH-banner, miner-API
 * válasz) keressük a bejegyzések "match" kulcsszavait; a leghosszabb egyező kulcsszó nyer (a
 * specifikusabb). A saját (user) bejegyzés azonos erősségnél megelőzi az alaplistát.
 */
class DeviceSpeedCatalog(private val context: Context, private val userFile: File) {

    @Volatile
    private var assetEntries: List<DeviceSpeedEntry>? = null

    fun all(): List<DeviceSpeedEntry> = userEntries() + assets()

    fun assets(): List<DeviceSpeedEntry> {
        assetEntries?.let { return it }
        val list = try {
            val text = context.assets.open("device_speed_catalog.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseEntries(JSONObject(text).optJSONArray("entries") ?: JSONArray(), "catalog")
        } catch (e: Exception) {
            emptyList()
        }
        assetEntries = list
        return list
    }

    @Synchronized
    fun userEntries(): List<DeviceSpeedEntry> {
        val root = readUserRoot()
        return parseEntries(root.optJSONArray("entries") ?: JSONArray(), "user")
    }

    /** Egy eszköz egyeztetése az ujjlenyomata alapján; null, ha nincs egyezés. */
    fun match(fingerprint: String): DeviceSpeedEntry? {
        if (fingerprint.isBlank()) return null
        val fp = fingerprint.lowercase()
        var best: DeviceSpeedEntry? = null
        var bestLen = 0
        for (e in all()) { // user előbb -> azonos hossznál az marad
            for (kw in e.match) {
                val k = kw.lowercase().trim()
                if (k.length >= 3 && fp.contains(k) && k.length > bestLen) {
                    best = e
                    bestLen = k.length
                }
            }
        }
        return best
    }

    /** Saját bejegyzés felvétele/felülírása (azonos id esetén). */
    @Synchronized
    fun upsertUserEntry(entry: DeviceSpeedEntry) {
        val root = readUserRoot()
        val arr = root.optJSONArray("entries") ?: JSONArray()
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != entry.id) kept.put(o)
        }
        kept.put(encodeEntry(entry))
        root.put("entries", kept)
        writeUserRoot(root)
    }

    // ------------------------------------------------------------------ saját mérések ("measured")

    @Synchronized
    fun measured(networkKey: String? = null): List<MeasuredDevice> {
        val arr = readUserRoot().optJSONArray("measured") ?: JSONArray()
        val list = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { decodeMeasured(it) } }
        return if (networkKey == null) list else list.filter { it.networkKey == networkKey }
    }

    /** A LAN-mérés eredményeinek rögzítése: eszközönként (hálózat + IP) a legjobb becsült sebesség és RTT. */
    @Synchronized
    fun recordMeasurements(networkKey: String, results: List<LanMeasurement>) {
        if (results.isEmpty()) return
        val root = readUserRoot()
        val existing = (root.optJSONArray("measured") ?: JSONArray()).let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { decodeMeasured(it) } }
        }.associateBy { it.networkKey + "|" + it.ip }.toMutableMap()
        val now = System.currentTimeMillis()
        for (r in results) {
            val key = "$networkKey|${r.ip}"
            val prev = existing[key]
            existing[key] = MeasuredDevice(
                networkKey = networkKey,
                ip = r.ip,
                label = r.label,
                catalogId = r.catalog?.id ?: prev?.catalogId,
                lastMs = now,
                bestPathMbps = listOfNotNull(prev?.bestPathMbps, r.pathEstimateMbps).maxOrNull(),
                minRttMs = listOfNotNull(prev?.minRttMs, r.rttSmallMinMs).minOrNull(),
                lastLossPct = r.lossPct,
                runs = (prev?.runs ?: 0) + 1,
            )
        }
        val arr = JSONArray()
        existing.values.sortedByDescending { it.lastMs }.take(500).forEach { arr.put(encodeMeasured(it)) }
        root.put("measured", arr)
        writeUserRoot(root)
    }

    // ------------------------------------------------------------------ JSON

    private fun readUserRoot(): JSONObject = try {
        if (userFile.exists()) JSONObject(userFile.readText(Charsets.UTF_8)) else newUserRoot()
    } catch (e: Exception) {
        newUserRoot()
    }

    private fun newUserRoot(): JSONObject = JSONObject().apply {
        put("version", 1)
        put(
            "description",
            "Saját bővítések a gyártó-specifikus port-sebesség listához (entries) és a saját LAN-mérések (measured). " +
                "Az entries ugyanolyan szerkezetű, mint az app assets/device_speed_catalog.json fájlja."
        )
        put("entries", JSONArray())
        put("measured", JSONArray())
    }

    private fun writeUserRoot(root: JSONObject) {
        userFile.parentFile?.mkdirs()
        val tmp = File(userFile.parentFile, userFile.name + ".tmp")
        tmp.writeText(root.toString(2), Charsets.UTF_8)
        if (!tmp.renameTo(userFile)) {
            userFile.writeText(root.toString(2), Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun parseEntries(arr: JSONArray, source: String): List<DeviceSpeedEntry> =
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val matchArr = o.optJSONArray("match") ?: JSONArray()
            DeviceSpeedEntry(
                id = o.optString("id").ifBlank { "entry_$i" },
                vendor = o.optString("vendor"),
                model = o.optString("model"),
                category = o.optString("category"),
                portMbps = if (o.has("portMbps") && !o.isNull("portMbps")) o.optInt("portMbps") else null,
                wifiMbps = if (o.has("wifiMbps") && !o.isNull("wifiMbps")) o.optInt("wifiMbps") else null,
                match = (0 until matchArr.length()).map { matchArr.optString(it) }.filter { it.isNotBlank() },
                confidence = o.optString("confidence", "?"),
                notes = o.optString("notes"),
                source = source,
            )
        }

    private fun encodeEntry(e: DeviceSpeedEntry): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("vendor", e.vendor)
        put("model", e.model)
        put("category", e.category)
        put("portMbps", e.portMbps ?: JSONObject.NULL)
        put("wifiMbps", e.wifiMbps ?: JSONObject.NULL)
        put("match", JSONArray(e.match))
        put("confidence", e.confidence)
        put("notes", e.notes)
    }

    private fun encodeMeasured(m: MeasuredDevice): JSONObject = JSONObject().apply {
        put("networkKey", m.networkKey)
        put("ip", m.ip)
        put("label", m.label)
        put("catalogId", m.catalogId ?: JSONObject.NULL)
        put("lastMs", m.lastMs)
        put("bestPathMbps", m.bestPathMbps ?: JSONObject.NULL)
        put("minRttMs", m.minRttMs ?: JSONObject.NULL)
        put("lastLossPct", m.lastLossPct)
        put("runs", m.runs)
    }

    private fun decodeMeasured(o: JSONObject): MeasuredDevice = MeasuredDevice(
        networkKey = o.optString("networkKey"),
        ip = o.optString("ip"),
        label = o.optString("label"),
        catalogId = if (o.isNull("catalogId")) null else o.optString("catalogId").ifBlank { null },
        lastMs = o.optLong("lastMs"),
        bestPathMbps = if (o.isNull("bestPathMbps")) null else o.optDouble("bestPathMbps").takeIf { !it.isNaN() },
        minRttMs = if (o.isNull("minRttMs")) null else o.optDouble("minRttMs").takeIf { !it.isNaN() },
        lastLossPct = o.optDouble("lastLossPct", 0.0),
        runs = o.optInt("runs", 1),
    )
}
