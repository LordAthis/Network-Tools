// Verzio: v0.4.0 - 2026-09-21
package hu.lordathis.networktools.engine

/**
 * AZ ALAPÉRTELMEZÉSEK EGY HELYEN. Egy forkban elég ezt a fájlt átírni: a Beállítások panel
 * ezekkel indul, amíg a felhasználó nem választ mást. (A színek: ui/Theme.kt.)
 */
object Defaults {
    /** Megjelenés: "SYSTEM" (a rendszer színe), "DARK" vagy "LIGHT". */
    const val SKIN = "SYSTEM"

    /**
     * E-mail címzettek indulóban: csak MINTA címek (example.com), a Beállítások > E-mail beállítások alatt
     * felvehetők és törölhetők. (A repó publikus, ezért valódi cím itt nincs.)
     */
    val EMAIL_SAMPLES = listOf(
        "minta.cimzett@example.com",
        "masik.minta@example.com",
    )

    /** Háttérben futás alapból ki van kapcsolva. */
    const val BACKGROUND_RUN = false

    /** Értesítési hang alapból be van kapcsolva (a rendszer alapértelmezett értesítési hangja). */
    const val NOTIFICATION_SOUND_ENABLED = true
}
