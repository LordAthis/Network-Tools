// Verzio: v0.1.0 - 2026-09-27
package hu.lordathis.networktools.miner

import hu.lordathis.networktools.network.MinerApiProbe
import org.json.JSONArray
import org.json.JSONObject

/** Egy pool-bejegyzés a miner "pools" válaszából. */
data class MinerPool(val url: String, val user: String, val status: String, val accepted: Long?, val rejected: Long?)

/** Egy miner legutóbbi részletes lekérdezésének eredménye (version + summary + pools + stats). */
data class MinerStatus(
    val ip: String,
    val port: Int?,
    val model: String?,
    val firmware: String?,
    val hashrate5s: String?,
    val hashrateAvg: String?,
    val elapsedSec: Long?,
    val accepted: Long?,
    val rejected: Long?,
    val hwErrors: Long?,
    val fans: List<Int>,
    val temps: List<Double>,
    val pools: List<MinerPool>,
    val error: String?,
    val queriedMs: Long,
) {
    val ok: Boolean get() = error == null

    /** Rövid, egysoros állapot (Gyorsjelentés / lista). */
    val headline: String
        get() = if (!ok) "hiba: $error" else listOfNotNull(
            model,
            hashrate5s?.let { "5s: $it" } ?: hashrateAvg?.let { "átl.: $it" },
            temps.maxOrNull()?.let { "max ${"%.0f".format(it)}°C" },
            fans.takeIf { it.isNotEmpty() }?.let { "ventilátor ${it.minOrNull()}-${it.maxOrNull()} rpm" },
            pools.firstOrNull { it.status.equals("Alive", true) }?.let { "pool OK" } ?: pools.takeIf { it.isNotEmpty() }?.let { "pool: ${it.first().status}" },
        ).joinToString(" · ")
}

/**
 * A Miner's panel lekérdezője: a cgminer/bmminer-kompatibilis JSON-over-TCP API (4028/4433) alapján
 * - ugyanaz a protokoll, mint a Windowsos MinerStatus.ps1-ben. Csak OLVAS (version/summary/pools/stats),
 * semmilyen beállítást nem módosít a gépen.
 */
object MinerService {

    /** A részletes lekérdezés teszt-azonosítója (a teszt-motorban, a Kezdőlap termináljában ezzel fut). */
    const val QUERY_TEST_ID = "miner_query"

    suspend fun query(ip: String): MinerStatus {
        val now = System.currentTimeMillis()
        val version = MinerApiProbe.PORTS.firstNotNullOfOrNull { port -> MinerApiProbe.query(ip, port, "version", 2500) }
            ?: return MinerStatus(ip, null, null, null, null, null, null, null, null, null, emptyList(), emptyList(), emptyList(), "a miner API (4028/4433) nem válaszol", now)
        val port = version.port
        val summary = MinerApiProbe.query(ip, port, "summary", 3000)
        val pools = MinerApiProbe.query(ip, port, "pools", 3000)
        val stats = MinerApiProbe.query(ip, port, "stats", 4000)

        val vObj = parse(version.rawResponse)?.let { firstOf(it, "VERSION") }
        val sObj = summary?.let { parse(it.rawResponse) }?.let { firstOf(it, "SUMMARY") }
        val statsArr = stats?.let { parse(it.rawResponse) }?.optJSONArray("STATS")
        val statsObjs = if (statsArr == null) emptyList() else (0 until statsArr.length()).mapNotNull { statsArr.optJSONObject(it) }

        val model = vObj?.let { v -> listOf("Type", "Model", "Miner", "PROD").firstNotNullOfOrNull { k -> v.optString(k).takeIf { it.isNotBlank() } } }
            ?: statsObjs.firstNotNullOfOrNull { it.optString("Type").takeIf { t -> t.isNotBlank() } }
        val firmware = vObj?.let { v ->
            listOf("CompileTime", "Firmware", "BMMiner", "CGMiner", "LUXminer").firstNotNullOfOrNull { k -> v.optString(k).takeIf { it.isNotBlank() }?.let { "$k $it" } }
        }

        val fans = ArrayList<Int>()
        val temps = ArrayList<Double>()
        for (o in statsObjs + listOfNotNull(sObj)) {
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val lk = k.lowercase()
                if (FAN_KEY.matches(lk)) {
                    val v = o.optInt(k, 0)
                    if (v in 1..30_000) fans += v
                } else if (TEMP_KEY.matches(lk)) {
                    val v = o.optDouble(k, 0.0)
                    if (!v.isNaN() && v in 1.0..150.0) temps += v
                }
            }
        }

        val poolList = pools?.let { parse(it.rawResponse) }?.optJSONArray("POOLS")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { p ->
                MinerPool(
                    url = p.optString("URL"),
                    user = p.optString("User"),
                    status = p.optString("Status", "?"),
                    accepted = p.optLong("Accepted", -1).takeIf { it >= 0 },
                    rejected = p.optLong("Rejected", -1).takeIf { it >= 0 },
                )
            }
        } ?: emptyList()

        return MinerStatus(
            ip = ip,
            port = port,
            model = model,
            firmware = firmware,
            hashrate5s = hashrate(sObj, "5s") ?: statsObjs.firstNotNullOfOrNull { hashrate(it, "5s") },
            hashrateAvg = hashrate(sObj, "av"),
            elapsedSec = sObj?.optLong("Elapsed", -1)?.takeIf { it >= 0 },
            accepted = sObj?.optLong("Accepted", -1)?.takeIf { it >= 0 },
            rejected = sObj?.optLong("Rejected", -1)?.takeIf { it >= 0 },
            hwErrors = sObj?.optLong("Hardware Errors", -1)?.takeIf { it >= 0 },
            fans = fans.distinct(),
            temps = temps,
            pools = poolList,
            error = if (summary == null && stats == null && pools == null) "csak a version válaszolt" else null,
            queriedMs = now,
        )
    }

    private val FAN_KEY = Regex("""fan\d+|fan_\d+|fan speed (in|out)""")
    private val TEMP_KEY = Regex("""temp\d*(_\d+)?|temp2_\d+|temperature|chip_temp\d*""")

    /** A cgminer-család néha hibás JSON-t küld (pl. hiányzó vessző "}{" között) - ezt javítjuk. */
    private fun parse(raw: String): JSONObject? = try {
        JSONObject(raw.replace("}{", "},{").trim('\u0000', ' ', '\n', '\r'))
    } catch (e: Exception) {
        null
    }

    private fun firstOf(root: JSONObject, key: String): JSONObject? = (root.opt(key) as? JSONArray)?.optJSONObject(0)

    /** "GHS 5s" / "MHS 5s" / "GHS av" / "MHS av" -> olvasható TH/s / GH/s. */
    private fun hashrate(o: JSONObject?, which: String): String? {
        if (o == null) return null
        val ghs = o.optString("GHS $which").toDoubleOrNull()
        val mhs = o.optString("MHS $which").toDoubleOrNull()
        val ghsValue = ghs ?: mhs?.let { it / 1000.0 } ?: return null
        if (ghsValue <= 0) return null
        return when {
            ghsValue >= 1000 -> "%.2f TH/s".format(ghsValue / 1000)
            ghsValue >= 1 -> "%.1f GH/s".format(ghsValue)
            else -> "%.1f MH/s".format(ghsValue * 1000)
        }
    }
}

/** Egy valószínű miner a hálózaton - a korábbi tesztek adataiból összeválogatva (lásd [MinerService.candidates]). */
data class MinerCandidate(
    val host: hu.lordathis.networktools.speed.LanHostInfo,
    val catalogModel: String?,
    val reason: String,
)

/** A miner-jelöltek kiválogatása a LAN-eszközlistából (miner API, 4028-as port, név/cím/banner, gyártói lista). */
fun minerCandidates(
    hosts: List<hu.lordathis.networktools.speed.LanHostInfo>,
    catalog: hu.lordathis.networktools.speed.DeviceSpeedCatalog,
): List<MinerCandidate> {
    val keywords = listOf(
        "antminer", "whatsminer", "btminer", "avalon", "bitaxe", "axeos", "nerdaxe", "iceriver", "goldshell",
        "bmminer", "cgminer", "braiins", "luxos", "vnish", "innosilicon", "bitmain", "microbt", "bitdeer", "sealminer",
    )
    return hosts.mapNotNull { h ->
        val fp = h.fingerprint.lowercase()
        val cat = catalog.match(h.fingerprint)
        val reason = when {
            h.minerInfo != null -> "miner API válaszolt"
            h.openPorts?.split(",")?.map { it.trim() }?.any { it == "4028" || it == "4433" } == true -> "nyitott 4028/4433 port"
            cat != null && cat.category == "miner" -> "gyártói lista: ${cat.vendor} ${cat.model}"
            keywords.any { fp.contains(it) } -> "név / web-cím / banner alapján"
            else -> null
        } ?: return@mapNotNull null
        MinerCandidate(h, cat?.takeIf { it.category == "miner" }?.let { "${it.vendor} ${it.model}" }, reason)
    }
}
