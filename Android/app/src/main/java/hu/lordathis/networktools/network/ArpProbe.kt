// Verzio: v0.5.0 - 2026-09-22
package hu.lordathis.networktools.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * EGYSZERI, induláskor lefutó próba (,,spike''): tényleg olvasható-e a rendszer ARP-táblája
 * (`/proc/net/arp`) egy nem-root Android appból. Ha igen, MAC-cím alapú eszközfelismerés
 * (gyártó/OUI, "néma" eszközök) is megvalósítható; ha nem, ezek a funkciók Androidon nem
 * érhetők el root nélkül - ezt a LOG rögzíti, hogy a következő körben el lehessen dönteni.
 *
 * Csak EGYSZER fut (lásd [ArpProbeState] - JSON-flag), utána a naplóból már tudható az eredmény.
 */
object ArpProbe {

    data class ArpProbeResult(
        val fileReadable: Boolean,
        val lineCount: Int,
        val sampleEntries: List<ArpEntry>,
        val errorMessage: String?,
    )

    data class ArpEntry(val ip: String, val mac: String, val device: String)

    private val ARP_LINE = Regex("""^(\S+)\s+0x\S+\s+0x\S+\s+(\S+)\s+\S+\s+(\S+)""")

    suspend fun run(): ArpProbeResult = withContext(Dispatchers.IO) {
        val file = File("/proc/net/arp")
        try {
            if (!file.exists()) {
                return@withContext ArpProbeResult(false, 0, emptyList(), "A /proc/net/arp nem létezik ezen az eszközön.")
            }
            val lines = file.readLines()
            val entries = lines.drop(1).mapNotNull { line ->
                ARP_LINE.find(line)?.let { m ->
                    val mac = m.groupValues[2]
                    if (mac == "00:00:00:00:00:00") null else ArpEntry(m.groupValues[1], mac, m.groupValues[3])
                }
            }
            ArpProbeResult(
                fileReadable = true,
                lineCount = lines.size,
                sampleEntries = entries.take(10),
                errorMessage = if (entries.isEmpty()) "A fájl olvasható, de nincs benne (nem nulla MAC-ű) bejegyzés." else null,
            )
        } catch (e: SecurityException) {
            ArpProbeResult(false, 0, emptyList(), "Hozzáférés megtagadva (SecurityException): ${e.message}")
        } catch (e: Exception) {
            ArpProbeResult(false, 0, emptyList(), "Váratlan hiba: ${e.message}")
        }
    }
}
