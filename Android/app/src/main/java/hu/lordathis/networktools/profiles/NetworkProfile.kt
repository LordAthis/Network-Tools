// Verzio: v0.5.0 - 2026-09-22
package hu.lordathis.networktools.profiles

/**
 * Egy hálózaton belül ismert eszköz (router, switch, miner, stb.) - a Windows-os
 * DeviceList.ps1/Devices.json MAC-regiszterének Android-megfelelője, de PROFILONKÉNT (hálózatonként)
 * külön lista, nem egy közös, globális fájl.
 *
 * A [passwordEnc]/[accountEnc] mezők a [ProfileSecretCrypto]-val TITKOSÍTVA tárolódnak a lemezen -
 * a UI-nak sosem szabad nyers szöveget írnia ide, csak a store metódusain keresztül.
 */
data class ProfileDevice(
    val mac: String,
    val deviceName: String = "",
    val nick: String = "",
    val category: String = "",
    val accountEnc: String = "",
    val passwordEnc: String = "",
    val notes: String = "",
    val testRefs: List<String> = emptyList(),
    val firstSeenMs: Long = 0L,
    val lastSeenMs: Long = 0L,
) {
    /** A megjelenítendő név: Nick, ha van; különben az eszköznév; végső esetben a MAC. */
    val displayName: String get() = nick.ifBlank { deviceName.ifBlank { mac } }
}

/**
 * Egy hálózat ("profil"): azonosítás SSID + BSSID alapján (lásd [ProfileStore] az egyeztetési
 * logikáért), a hozzá tartozó eszközlistával.
 */
data class NetworkProfile(
    val id: String,
    val ssid: String? = null,
    val bssid: String? = null,
    val nick: String = "",
    val active: Boolean = true,
    val devices: List<ProfileDevice> = emptyList(),
    val notes: String = "",
    val createdMs: Long = 0L,
    val updatedMs: Long = 0L,
) {
    /** Megjelenítési sorrend: Nick -> SSID -> BSSID. */
    val displayName: String
        get() = nick.ifBlank { ssid?.takeIf { it.isNotBlank() } ?: shortLabel() }

    /** "Ismeretlen-<BSSID utolsó 6 hexe>" - hogy több, még el nem nevezett hálózat is megkülönböztethető maradjon. */
    fun shortLabel(): String {
        val macSuffix = bssid?.replace(":", "")?.takeLast(6)?.uppercase()
        return if (ssid.isNullOrBlank()) "Ismeretlen" + (macSuffix?.let { "-$it" } ?: "") else ssid
    }

    /** Fájl-/naplónévbe illő, biztonságos alak (space és speciális karakterek nélkül). */
    fun logSafeName(): String =
        displayName.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifBlank { "Ismeretlen" }
}
