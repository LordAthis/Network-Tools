// Verzio: v0.1.0 - 2026-09-21
package hu.lordathis.networktools.storage

/**
 * Első indítás / frissítés felismerése. Az adatokat (beállítások, jegyzetek, naplók) a frissítés maga
 * nem bántja - ez az osztály csak azt mondja meg, mi történt, és ad egy pontot a jövőbeli
 * adatformátum-migrációknak ([Migrations]).
 */
data class InstallInfo(
    val previousVersionCode: Int,
    val previousVersionName: String?,
    val currentVersionCode: Int,
    val currentVersionName: String,
) {
    val isFirstRun: Boolean get() = previousVersionCode < 0
    val isUpdate: Boolean get() = previousVersionCode in 0 until currentVersionCode
    val isDowngrade: Boolean get() = previousVersionCode > currentVersionCode

    fun describe(): String = when {
        isFirstRun -> "Első indítás (v$currentVersionName)."
        isUpdate -> "Frissítés: v${previousVersionName ?: previousVersionCode} -> v$currentVersionName (az adatok megmaradtak)."
        isDowngrade -> "Visszalépés régebbi verzióra: v${previousVersionName ?: previousVersionCode} -> v$currentVersionName."
        else -> "Indítás (v$currentVersionName)."
    }

    companion object {
        fun detect(
            storedVersionCode: Int,
            storedVersionName: String?,
            currentVersionCode: Int,
            currentVersionName: String,
        ): InstallInfo {
            val (prevCode, prevName) = if (storedVersionCode >= 0) storedVersionCode to storedVersionName else -1 to null
            return InstallInfo(prevCode, prevName, currentVersionCode, currentVersionName)
        }
    }
}

/** Adatformátum-migrációk helye. Most még nincs mit migrálni - a keret viszont kész. */
object Migrations {
    fun run(info: InstallInfo, @Suppress("UNUSED_PARAMETER") storage: AppStorage, log: (String) -> Unit) {
        if (!info.isUpdate) return
        // Példa a jövőre:
        // if (info.previousVersionCode < 10002) { ... átalakítás ... }
        log("Migráció: nincs teendő (${info.previousVersionName} -> ${info.currentVersionName}).")
    }
}
