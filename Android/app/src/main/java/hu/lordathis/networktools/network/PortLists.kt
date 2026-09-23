// Verzio: v0.5.0 - 2026-09-22
package hu.lordathis.networktools.network

/** Port-scan mód: csak a listán szereplő portok, vagy "all" (a teljes 1-65535 tartomány). */
sealed class PortScanTarget {
    data class ListPorts(val ports: List<Int>) : PortScanTarget()
    object AllPorts : PortScanTarget()

    fun resolve(): List<Int> = when (this) {
        is ListPorts -> ports
        AllPorts -> (1..65535).toList()
    }
}

/** Alapértelmezett portlisták (a NetworkDiag_MAX_v2.ps1 §6/B ismert+gyanús portjai alapján). */
object PortLists {
    val COMMON = listOf(21, 22, 23, 25, 53, 80, 110, 139, 143, 443, 445, 3389, 8080, 8443)

    /** cgminer/bmminer (4028), Whatsminer (4433), és tipikus web-UI-k (80/8080), stratum (3333/4444/14444). */
    val MINER = listOf(80, 8080, 4028, 4433, 3333, 4444, 14444)

    val DEFAULT = (COMMON + MINER).distinct().sorted()
}
