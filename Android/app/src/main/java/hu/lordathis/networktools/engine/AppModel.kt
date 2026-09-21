// Verzio: v0.4.0 - 2026-09-21
package hu.lordathis.networktools.engine

/** Amit a Beállítások > Mentési beállítások alatt a felhasználó fájlba menthet / e-mailezhet. */
enum class ExportKind(val label: String, val fileStem: String, val extension: String, val mimeType: String) {
    LOG("Napló", "networktools-naplo", "txt", "text/plain"),
}

/** A Kezdőlap visszajelzés-sávjának szintjei (a szegély színe). */
enum class FeedLevel { INFO, OK, WARN, ERROR }

/**
 * Egy visszajelzés a Kezdőlapon: az app eseményei, és (később) a háttérben futó tesztek eredményei /
 * részeredményei. [source]: honnan jön (pl. "Adatmentés", "Napló", "Teszt").
 */
data class FeedEntry(
    val timeMs: Long,
    val source: String,
    val text: String,
    val level: FeedLevel,
)

/** Egy naplófájl a telefonon (a Beállítások listájához). */
data class LogFileInfo(val name: String, val sizeBytes: Long, val modifiedMs: Long)
