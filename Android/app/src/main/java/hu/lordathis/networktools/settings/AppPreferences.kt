// Verzio: v0.4.0 - 2026-09-21
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

    companion object {
        private const val KEY_SKIN = "skin"
        private const val KEY_EMAIL_RECIPIENTS = "email_recipients"
        private const val KEY_EMAIL_SELECTED = "email_selected"
        private const val KEY_EMAIL_CONFIRMED = "email_confirmed"
        private const val KEY_BACKGROUND_RUN = "background_run"
        private const val KEY_NOTIF_SOUND_ENABLED = "notif_sound_enabled"
        private const val KEY_NOTIF_SOUND_URI = "notif_sound_uri"
        private const val KEY_NOTIF_CHANNEL_VERSION = "notif_channel_version"
    }
}
