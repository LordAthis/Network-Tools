// Verzio: v0.7.0 - 2026-09-27
package hu.lordathis.networktools.settings

import android.content.Context
import android.content.SharedPreferences
import hu.lordathis.networktools.engine.Defaults

/** Egyszerű, helyi (SharedPreferences-alapú) beállítás-tár. */
class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("networktools_prefs", Context.MODE_PRIVATE)

    /** Megjelenés (skin): "SYSTEM" (alapértelmezett, a rendszer színe), "DARK" vagy "LIGHT". */
    var skin: String
        get() = prefs.getString(KEY_SKIN, Defaults.SKIN) ?: Defaults.SKIN
        set(value) = prefs.edit().putString(KEY_SKIN, value).apply()

    // ------------------------------------------------------------------ E-mail

    /**
     * E-mail címzettek listája. Az első indításkor a minta címek ([Defaults.EMAIL_SAMPLES]); utána a
     * felhasználó felvehet/törölhet (a lista akár teljesen üres is lehet).
     */
    var emailRecipients: List<String>
        get() {
            val stored = prefs.getString(KEY_EMAIL_RECIPIENTS, null) ?: return Defaults.EMAIL_SAMPLES
            return stored.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        }
        set(value) = prefs.edit().putString(KEY_EMAIL_RECIPIENTS, value.joinToString("\n")).apply()

    /** A kiválasztott (küldéshez használt) címzett; üres/érvénytelen esetén a lista első eleme. */
    var emailSelected: String
        get() = prefs.getString(KEY_EMAIL_SELECTED, "") ?: ""
        set(value) = prefs.edit().putString(KEY_EMAIL_SELECTED, value).apply()

    /** Azok a címek, amelyekről a felhasználó megerősítette, hogy a tesztlevél megérkezett (vesszővel elválasztva). */
    var emailConfirmed: Set<String>
        get() = (prefs.getString(KEY_EMAIL_CONFIRMED, "") ?: "").split(",").filter { it.isNotBlank() }.toSet()
        set(value) = prefs.edit().putString(KEY_EMAIL_CONFIRMED, value.joinToString(",")).apply()

    // ------------------------------------------------------------------ Háttérben futás, értesítések

    /** Háttérben futás (előtér-szolgáltatás állandó értesítéssel). */
    var backgroundRun: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_RUN, Defaults.BACKGROUND_RUN)
        set(value) = prefs.edit().putBoolean(KEY_BACKGROUND_RUN, value).apply()

    /** Értesítési hang be/ki. */
    var notificationSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_SOUND_ENABLED, Defaults.NOTIFICATION_SOUND_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_SOUND_ENABLED, value).apply()

    /** A választott értesítési hang URI-ja; üres = a rendszer alapértelmezett értesítési hangja. */
    var notificationSoundUri: String
        get() = prefs.getString(KEY_NOTIF_SOUND_URI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NOTIF_SOUND_URI, value).apply()

    /** Az értesítési csatorna verziója: Androidon a csatorna hangja utólag nem módosítható, ezért új csatorna készül. */
    var notificationChannelVersion: Int
        get() = prefs.getInt(KEY_NOTIF_CHANNEL_VERSION, 0)
        set(value) = prefs.edit().putInt(KEY_NOTIF_CHANNEL_VERSION, value).apply()

    // ------------------------------------------------------------------ Hálózati beállítások

    /** A WiFi/mobilnet "erre a hálózatra kényszerítés" állapota: "NONE" | "WIFI" | "CELLULAR". */
    var forcedTransport: String
        get() = prefs.getString(KEY_FORCED_TRANSPORT, "NONE") ?: "NONE"
        set(value) = prefs.edit().putString(KEY_FORCED_TRANSPORT, value).apply()

    /** Bluetooth kapcsoló (egyelőre csak a kapcsoló állapota - a tényleges BLE-funkciók később). */
    var bluetoothEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLUETOOTH_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BLUETOOTH_ENABLED, value).apply()

    /** Port-scan mód: "LIST" (csak a listán szereplő portok) vagy "ALL" (1-65535). */
    var portScanMode: String
        get() = prefs.getString(KEY_PORT_SCAN_MODE, "LIST") ?: "LIST"
        set(value) = prefs.edit().putString(KEY_PORT_SCAN_MODE, value).apply()

    /** Egyedi portlista, vesszővel elválasztva; üres = az alapértelmezett (PortLists.DEFAULT). */
    var customPortList: String
        get() = prefs.getString(KEY_CUSTOM_PORT_LIST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_PORT_LIST, value).apply()

    /** Egyidejű vizsgálatok száma (throttle) ping-sweepnél/port-scannél. */
    var scanConcurrency: Int
        get() = prefs.getInt(KEY_SCAN_CONCURRENCY, 32)
        set(value) = prefs.edit().putInt(KEY_SCAN_CONCURRENCY, value.coerceIn(1, 128)).apply()

    /** Extra, kézzel megadott alhálók (pl. "192.168.5.0/24"), soronként - a jelenlegi mellett ezeket is vizsgálja. */
    var extraSubnets: String
        get() = prefs.getString(KEY_EXTRA_SUBNETS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_EXTRA_SUBNETS, value).apply()

    /**
     * SSH-bejelentkezés engedélyezése: MOST MÉG NINCS mögötte funkció (a hitelesítő-trezorral együtt
     * készül el), a Beállítások ezt inaktív/letiltott csúszkaként mutatja - az érték itt már tárolva
     * van, hogy a UI-nak legyen mihez kötnie, mire a funkció elkészül.
     */
    var sshLoginEnabled: Boolean
        get() = prefs.getBoolean(KEY_SSH_LOGIN_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SSH_LOGIN_ENABLED, value).apply()

    /**
     * Az egyszeri ARP-tábla olvashatósági próba ("spike") lefutott-e már. Ha igen, a következő
     * induláskor NEM fut le újra - az eredmény a LOG-ban (log/tests/) marad megnézhető.
     */
    var arpSpikeRan: Boolean
        get() = prefs.getBoolean(KEY_ARP_SPIKE_RAN, false)
        set(value) = prefs.edit().putBoolean(KEY_ARP_SPIKE_RAN, value).apply()

    /**
     * Egyszerű, gyors, alacsony hatású tesztek automatikus futtatása induláskor és rendszeresen.
     * Ezek NEM jelennek meg a kézi fiókokban - lásd a teszt-rangsorolási elemzést.
     */
    var autoTestsEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_TESTS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_TESTS_ENABLED, value).apply()

    /** Ismétlési köz percben (5-120). Ha az automatikus tesztek együttes fut. ideje ennél nagyobb lenne, a tényleges köz +5 perccel megnő. */
    var autoTestsIntervalMinutes: Int
        get() = prefs.getInt(KEY_AUTO_TESTS_INTERVAL, 15)
        set(value) = prefs.edit().putInt(KEY_AUTO_TESTS_INTERVAL, value.coerceIn(5, 120)).apply()

    /** Az automatikus tesztek legutóbbi lefutásának ideje (ms) - a Gyorsjelentés-gomb "friss" jelzéséhez. */
    var lastAutoTestsRunMs: Long
        get() = prefs.getLong(KEY_LAST_AUTO_RUN, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_AUTO_RUN, value).apply()

    // ------------------------------------------------------------------ Sebességteszt

    /** M1 szerver: "CLOUDFLARE" | "CUSTOM" | "LAN_SERVER". */
    var speedServer: String
        get() = prefs.getString(KEY_SPEED_SERVER, "CLOUDFLARE") ?: "CLOUDFLARE"
        set(value) = prefs.edit().putString(KEY_SPEED_SERVER, value).apply()

    var speedCustomUrl: String
        get() = prefs.getString(KEY_SPEED_CUSTOM_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SPEED_CUSTOM_URL, value).apply()

    /** A PC-n futó win/SpeedServer.ps1 címe ("ip:port"). */
    var speedLanServer: String
        get() = prefs.getString(KEY_SPEED_LAN_SERVER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SPEED_LAN_SERVER, value).apply()

    var speedConnections: Int
        get() = prefs.getInt(KEY_SPEED_CONNECTIONS, 4)
        set(value) = prefs.edit().putInt(KEY_SPEED_CONNECTIONS, value.coerceIn(1, 16)).apply()

    var speedDurationSec: Int
        get() = prefs.getInt(KEY_SPEED_DURATION, 10)
        set(value) = prefs.edit().putInt(KEY_SPEED_DURATION, value.coerceIn(5, 60)).apply()

    var speedUpload: Boolean
        get() = prefs.getBoolean(KEY_SPEED_UPLOAD, true)
        set(value) = prefs.edit().putBoolean(KEY_SPEED_UPLOAD, value).apply()

    /** M2 preset neve (StabilityPreset). */
    var speedStabilityPreset: String
        get() = prefs.getString(KEY_SPEED_UDP_PRESET, "GENERAL_30") ?: "GENERAL_30"
        set(value) = prefs.edit().putString(KEY_SPEED_UDP_PRESET, value).apply()

    /** M2/M3 cél: "AUTO" | "GATEWAY" | "1.1.1.1" | "8.8.8.8" | egyedi IP/név. */
    var speedUdpTarget: String
        get() = prefs.getString(KEY_SPEED_UDP_TARGET, "AUTO") ?: "AUTO"
        set(value) = prefs.edit().putString(KEY_SPEED_UDP_TARGET, value).apply()

    var speedPingTarget: String
        get() = prefs.getString(KEY_SPEED_PING_TARGET, "GATEWAY") ?: "GATEWAY"
        set(value) = prefs.edit().putString(KEY_SPEED_PING_TARGET, value).apply()

    var speedPingIntervalMs: Int
        get() = prefs.getInt(KEY_SPEED_PING_INTERVAL, 500)
        set(value) = prefs.edit().putInt(KEY_SPEED_PING_INTERVAL, value.coerceIn(200, 5000)).apply()

    /** Kiesésnél rövid hangjelzés a kábelteszt alatt. */
    var speedPingSound: Boolean
        get() = prefs.getBoolean(KEY_SPEED_PING_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_SPEED_PING_SOUND, value).apply()

    /** A hálózaton belüli (LAN) mérés külön kapcsolója - alapból KI. */
    var speedLanEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPEED_LAN_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SPEED_LAN_ENABLED, value).apply()

    // ------------------------------------------------------------------ Külső szolgáltatók (AI API/MCP)

    /** API-kulcs egy majdani külső (AI) szolgáltatáshoz - egyelőre csak tárolt mező, funkció nélkül. */
    var externalApiKey: String
        get() = prefs.getString(KEY_EXTERNAL_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_EXTERNAL_API_KEY, value).apply()

    /** MCP-szerver címe - egyelőre csak tárolt mező, funkció nélkül. */
    var externalMcpServer: String
        get() = prefs.getString(KEY_EXTERNAL_MCP_SERVER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_EXTERNAL_MCP_SERVER, value).apply()

    companion object {
        private const val KEY_SKIN = "skin"
        private const val KEY_EMAIL_RECIPIENTS = "email_recipients"
        private const val KEY_EMAIL_SELECTED = "email_selected"
        private const val KEY_EMAIL_CONFIRMED = "email_confirmed"
        private const val KEY_BACKGROUND_RUN = "background_run"
        private const val KEY_NOTIF_SOUND_ENABLED = "notif_sound_enabled"
        private const val KEY_NOTIF_SOUND_URI = "notif_sound_uri"
        private const val KEY_NOTIF_CHANNEL_VERSION = "notif_channel_version"
        private const val KEY_FORCED_TRANSPORT = "forced_transport"
        private const val KEY_BLUETOOTH_ENABLED = "bluetooth_enabled"
        private const val KEY_PORT_SCAN_MODE = "port_scan_mode"
        private const val KEY_CUSTOM_PORT_LIST = "custom_port_list"
        private const val KEY_SCAN_CONCURRENCY = "scan_concurrency"
        private const val KEY_EXTRA_SUBNETS = "extra_subnets"
        private const val KEY_SSH_LOGIN_ENABLED = "ssh_login_enabled"
        private const val KEY_ARP_SPIKE_RAN = "arp_spike_ran"
        private const val KEY_AUTO_TESTS_ENABLED = "auto_tests_enabled"
        private const val KEY_AUTO_TESTS_INTERVAL = "auto_tests_interval"
        private const val KEY_LAST_AUTO_RUN = "last_auto_run"
        private const val KEY_SPEED_SERVER = "speed_server"
        private const val KEY_SPEED_CUSTOM_URL = "speed_custom_url"
        private const val KEY_SPEED_LAN_SERVER = "speed_lan_server"
        private const val KEY_SPEED_CONNECTIONS = "speed_connections"
        private const val KEY_SPEED_DURATION = "speed_duration"
        private const val KEY_SPEED_UPLOAD = "speed_upload"
        private const val KEY_SPEED_UDP_PRESET = "speed_udp_preset"
        private const val KEY_SPEED_UDP_TARGET = "speed_udp_target"
        private const val KEY_SPEED_PING_TARGET = "speed_ping_target"
        private const val KEY_SPEED_PING_INTERVAL = "speed_ping_interval"
        private const val KEY_SPEED_PING_SOUND = "speed_ping_sound"
        private const val KEY_SPEED_LAN_ENABLED = "speed_lan_enabled"
        private const val KEY_EXTERNAL_API_KEY = "external_api_key"
        private const val KEY_EXTERNAL_MCP_SERVER = "external_mcp_server"
    }
}
