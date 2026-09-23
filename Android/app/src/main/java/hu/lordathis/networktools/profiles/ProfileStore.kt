// Verzio: v0.5.0 - 2026-09-22
package hu.lordathis.networktools.profiles

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Egy hálózat-egyeztetés kimenete (lásd [ProfileStore.match]). */
sealed class ProfileMatch {
    /** Pontos találat: ugyanaz az SSID ÉS ugyanaz a BSSID, mint egy meglévő profilnál. */
    data class Exact(val profile: NetworkProfile) : ProfileMatch()

    /**
     * ÜTKÖZÉS: van egy meglévő profil UGYANAZZAL az SSID-vel, de MÁSIK BSSID-vel - vagyis ez
     * FIZIKAILAG MÁS hálózat, ami csak véletlenül (vagy szándékosan, pl. gyári alapnév) ugyanazt
     * a nevet sugározza. Ilyenkor NEM szabad összekeverni a két profil adatait (jelszavak!).
     */
    data class SsidCollision(val existing: NetworkProfile) : ProfileMatch()

    /** Nincs egyező profil - ismeretlen hálózat. */
    object Unknown : ProfileMatch()
}

/**
 * A hálózati profilok (`profiles/profiles.json`) betöltése/mentése és az egyeztetési logika.
 * Nem szálbiztos szimultán íráshoz - a hívó (AppHub) egy dedikált dispatcheren szekvenciálisan hívja.
 */
class ProfileStore(private val dir: File) {

    private val file = File(dir, "profiles.json")

    @Synchronized
    fun loadAll(): List<NetworkProfile> {
        if (!file.exists()) return emptyList()
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val arr = root.optJSONArray("profiles") ?: JSONArray()
            (0 until arr.length()).map { decodeProfile(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun saveAll(profiles: List<NetworkProfile>) {
        dir.mkdirs()
        val root = JSONObject()
        root.put("version", 1)
        val arr = JSONArray()
        for (p in profiles) arr.put(encodeProfile(p))
        root.put("profiles", arr)
        writeAtomically(root.toString(2))
    }

    /**
     * Egy SSID+BSSID-hez tartozó profil keresése. Az egyezés SZIGORÚ: mindkét mezőnek egyeznie kell
     * ahhoz, hogy [ProfileMatch.Exact] legyen - azonos SSID, eltérő BSSID [ProfileMatch.SsidCollision].
     */
    fun match(profiles: List<NetworkProfile>, ssid: String?, bssid: String?): ProfileMatch {
        if (ssid == null && bssid == null) return ProfileMatch.Unknown
        val sameKey = profiles.firstOrNull { it.ssid == ssid && it.bssid == bssid && (ssid != null || bssid != null) }
        if (sameKey != null) return ProfileMatch.Exact(sameKey)
        if (!ssid.isNullOrBlank()) {
            val sameSsid = profiles.firstOrNull { it.ssid == ssid && it.bssid != bssid }
            if (sameSsid != null) return ProfileMatch.SsidCollision(sameSsid)
        }
        return ProfileMatch.Unknown
    }

    /** Új, üres profil létrehozása (még nem menti a listába - a hívó dolga hozzáadni + saveAll). */
    fun newProfile(ssid: String?, bssid: String?, now: Long): NetworkProfile = NetworkProfile(
        id = UUID.randomUUID().toString(),
        ssid = ssid,
        bssid = bssid,
        createdMs = now,
        updatedMs = now,
    )

    // ---------------------------------------------------------------------------------------
    // Eszköz-mezők titkosítása/visszafejtése - a UI ezeken keresztül érje el, sosem közvetlenül.
    // ---------------------------------------------------------------------------------------

    fun encryptSecret(plain: String): String = ProfileSecretCrypto.encrypt(plain)
    fun decryptSecret(token: String): String = ProfileSecretCrypto.decrypt(token)

    // ---------------------------------------------------------------------------------------

    /** Null-biztos String-olvasás: hiányzó vagy JSON null mező esetén Kotlin `null` (nem az "üres" String eset). */
    private fun readNullableString(o: JSONObject, key: String): String? =
        if (o.has(key) && !o.isNull(key)) o.getString(key).takeIf { it.isNotEmpty() } else null

    private fun writeAtomically(text: String) {
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun encodeProfile(p: NetworkProfile): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("ssid", p.ssid ?: JSONObject.NULL)
        put("bssid", p.bssid ?: JSONObject.NULL)
        put("nick", p.nick)
        put("active", p.active)
        put("notes", p.notes)
        put("createdMs", p.createdMs)
        put("updatedMs", p.updatedMs)
        val devArr = JSONArray()
        for (d in p.devices) devArr.put(encodeDevice(d))
        put("devices", devArr)
    }

    private fun decodeProfile(o: JSONObject): NetworkProfile {
        val devArr = o.optJSONArray("devices") ?: JSONArray()
        val devices = (0 until devArr.length()).map { decodeDevice(devArr.getJSONObject(it)) }
        return NetworkProfile(
            id = o.getString("id"),
            ssid = readNullableString(o, "ssid"),
            bssid = readNullableString(o, "bssid"),
            nick = o.optString("nick", ""),
            active = o.optBoolean("active", true),
            notes = o.optString("notes", ""),
            createdMs = o.optLong("createdMs", 0L),
            updatedMs = o.optLong("updatedMs", 0L),
            devices = devices,
        )
    }

    private fun encodeDevice(d: ProfileDevice): JSONObject = JSONObject().apply {
        put("mac", d.mac)
        put("deviceName", d.deviceName)
        put("nick", d.nick)
        put("category", d.category)
        put("accountEnc", d.accountEnc)
        put("passwordEnc", d.passwordEnc)
        put("notes", d.notes)
        put("testRefs", JSONArray(d.testRefs))
        put("firstSeenMs", d.firstSeenMs)
        put("lastSeenMs", d.lastSeenMs)
    }

    private fun decodeDevice(o: JSONObject): ProfileDevice {
        val refsArr = o.optJSONArray("testRefs") ?: JSONArray()
        return ProfileDevice(
            mac = o.getString("mac"),
            deviceName = o.optString("deviceName", ""),
            nick = o.optString("nick", ""),
            category = o.optString("category", ""),
            accountEnc = o.optString("accountEnc", ""),
            passwordEnc = o.optString("passwordEnc", ""),
            notes = o.optString("notes", ""),
            testRefs = (0 until refsArr.length()).map { refsArr.getString(it) },
            firstSeenMs = o.optLong("firstSeenMs", 0L),
            lastSeenMs = o.optLong("lastSeenMs", 0L),
        )
    }
}
