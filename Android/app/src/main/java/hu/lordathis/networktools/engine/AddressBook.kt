// Verzio: v0.1.0 - 2026-09-28
package hu.lordathis.networktools.engine

/**
 * A Webolvasó cím-listáinak egy bejegyzése: vagy egy LAN-eszköz (IP), vagy egy megnyitott web-cím.
 * A [details] a különböző tesztek részadataiból ÖSSZESÍTETT ismeretek (név, SNMP, portok, web-cím,
 * SSH, miner, gyártói lista, saját mérés, legutóbbi miner-lekérdezés).
 */
data class AddressEntry(
    val kind: Kind,
    val address: String,
    val openUrl: String,
    val title: String,
    val networkKey: String?,
    val networkName: String?,
    val details: List<String>,
    val lastMs: Long,
) {
    enum class Kind { LAN, WEB }
}
