// Verzio: v0.1.0 - 2026-09-28
package hu.lordathis.networktools.link

import org.json.JSONArray
import org.json.JSONObject

/**
 * "Linkelés": a Network-Tools példányok (telefon <-> telefon, telefon <-> Windows, helyben vagy távol)
 * egymásra találása és egymás mérése. A részletes terv: Android/LINK_PROTOCOL.md.
 *
 * v1 (ez a kör - ELŐKÉSZÍTÉS, csak az Android-oldal):
 *  - UDP 47800: kis JSON-üzenetek (HELLO / HELLO_REPLY / PING / PONG / BYE);
 *  - felfedezés: HELLO broadcast a helyi hálózaton (255.255.255.255 és az alháló broadcast-címe),
 *    illetve közvetlenül megadott (távoli) címekre;
 *  - a látható példány minden HELLO-ra HELLO_REPLY-jal, minden PING-re PONG-gal válaszol (UDP-visszhang -
 *    ez adja a pontosabb kábel/stabilitás-mérés alapját a DNS-visszhang helyett).
 *  - TCP 47801: FENNTARTVA a következő körre (sávszélesség-mérés két példány között).
 */
object LinkProtocol {
    const val MAGIC = "NetworkTools-Link"
    const val VERSION = 1
    const val UDP_PORT = 47800
    const val TCP_PORT = 47801

    enum class Type { HELLO, HELLO_REPLY, PING, PONG, BYE }

    data class Message(
        val type: Type,
        val nodeId: String,
        val name: String,
        val platform: String,
        val app: String,
        val caps: List<String> = emptyList(),
        val seq: Int = 0,
        val ts: Long = System.currentTimeMillis(),
        /** PONG / HELLO_REPLY esetén: a kérdező nodeId-ja. */
        val replyTo: String? = null,
        /** PING/PONG kitöltés (a csomagméret-szimulációhoz); csak a hossza számít. */
        val pad: String? = null,
    )

    fun encode(m: Message): ByteArray = JSONObject().apply {
        put("nt", MAGIC)
        put("v", VERSION)
        put("type", m.type.name)
        put("id", m.nodeId)
        put("name", m.name)
        put("platform", m.platform)
        put("app", m.app)
        put("caps", JSONArray(m.caps))
        put("seq", m.seq)
        put("ts", m.ts)
        if (m.replyTo != null) put("replyTo", m.replyTo)
        if (m.pad != null) put("pad", m.pad)
    }.toString().toByteArray(Charsets.UTF_8)

    /** null, ha nem Network-Tools link-üzenet (más program csomagja ugyanazon a porton). */
    fun decode(data: ByteArray, length: Int): Message? = try {
        val o = JSONObject(String(data, 0, length, Charsets.UTF_8))
        if (o.optString("nt") != MAGIC) {
            null
        } else {
            val capsArr = o.optJSONArray("caps") ?: JSONArray()
            Message(
                type = Type.valueOf(o.getString("type")),
                nodeId = o.getString("id"),
                name = o.optString("name"),
                platform = o.optString("platform"),
                app = o.optString("app"),
                caps = (0 until capsArr.length()).map { capsArr.optString(it) },
                seq = o.optInt("seq"),
                ts = o.optLong("ts"),
                replyTo = if (o.has("replyTo")) o.optString("replyTo") else null,
                pad = if (o.has("pad")) o.optString("pad") else null,
            )
        }
    } catch (e: Exception) {
        null
    }
}

/** Egy megtalált másik Network-Tools példány. */
data class LinkPeer(
    val nodeId: String,
    val name: String,
    val platform: String,
    val app: String,
    val caps: List<String>,
    val address: String,
    val port: Int,
    val via: String,
    val lastSeenMs: Long,
    val rttMs: Double?,
)
