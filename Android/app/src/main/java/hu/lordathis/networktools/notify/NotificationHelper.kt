// Verzio: v0.1.0 - 2026-09-21
package hu.lordathis.networktools.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import hu.lordathis.networktools.R
import hu.lordathis.networktools.settings.AppPreferences

/**
 * Értesítések és értesítési hangok.
 *
 *  - BACKGROUND csatorna: a háttérben futás állandó (néma) értesítése.
 *  - "alerts_vN" csatorna: a tényleges értesítések (most csak a Beállítások > Értesítési hangok
 *    "PRÓBA" gombja használja). Androidon a csatorna hangja LÉTREHOZÁS UTÁN nem módosítható, ezért
 *    hangváltáskor új csatorna készül (növelt N), a régi törlődik.
 */
object NotificationHelper {
    const val CHANNEL_BACKGROUND = "background"
    private const val ALERT_PREFIX = "alerts_v"
    const val ID_BACKGROUND = 1001
    const val ID_TEST = 1002

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** A háttérben futás csatornája: alacsony fontosság, hang nélkül. */
    fun ensureBackgroundChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_BACKGROUND,
            "Háttérben futás",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Az állandó értesítés, amíg az app a háttérben fut."
            setSound(null, null)
        }
        manager(context).createNotificationChannel(channel)
    }

    /** A jelenlegi értesítési csatorna azonosítója (a beállítások szerint létrehozza, ha hiányzik). */
    fun ensureAlertChannel(context: Context, prefs: AppPreferences): String {
        val id = ALERT_PREFIX + prefs.notificationChannelVersion
        val nm = manager(context)
        if (nm.getNotificationChannel(id) == null) buildAlertChannel(context, prefs, id)
        return id
    }

    /** A hang-beállítás megváltozásakor hívandó: új csatorna a friss hanggal, a régi törlése. */
    fun rebuildAlertChannel(context: Context, prefs: AppPreferences): String {
        val nm = manager(context)
        nm.notificationChannels
            .filter { it.id.startsWith(ALERT_PREFIX) }
            .forEach { nm.deleteNotificationChannel(it.id) }
        prefs.notificationChannelVersion = prefs.notificationChannelVersion + 1
        val id = ALERT_PREFIX + prefs.notificationChannelVersion
        buildAlertChannel(context, prefs, id)
        return id
    }

    private fun buildAlertChannel(context: Context, prefs: AppPreferences, id: String) {
        val channel = NotificationChannel(id, "Értesítések", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Az app értesítései."
        }
        if (prefs.notificationSoundEnabled) {
            channel.setSound(
                soundUri(prefs),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        } else {
            channel.setSound(null, null)
        }
        manager(context).createNotificationChannel(channel)
    }

    /** A kiválasztott hang; ha nincs választás, a rendszer alapértelmezett értesítési hangja. */
    fun soundUri(prefs: AppPreferences): Uri =
        prefs.notificationSoundUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    /** A hang emberi neve a beállításokhoz ("Alapértelmezett", ha nincs választás). */
    fun soundTitle(context: Context, prefs: AppPreferences): String {
        if (prefs.notificationSoundUri.isBlank()) return "Alapértelmezett"
        return try {
            RingtoneManager.getRingtone(context, soundUri(prefs))?.getTitle(context) ?: "Egyedi hang"
        } catch (e: Exception) {
            "Egyedi hang"
        }
    }

    /** A háttérben futás állandó értesítése (a szolgáltatás ezzel lép előtérbe). */
    fun buildBackgroundNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_BACKGROUND)
            .setSmallIcon(R.drawable.ic_stat_network)
            .setContentTitle("Network Tool's")
            .setContentText("Fut a háttérben.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /**
     * Próba-értesítés a beállított hanggal. Visszaadja, hogy elküldhető volt-e
     * (Android 13+: értesítési engedély kell).
     */
    fun postTest(context: Context, prefs: AppPreferences): Boolean {
        if (!hasPermission(context)) return false
        val channelId = ensureAlertChannel(context, prefs)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_network)
            .setContentTitle("Network Tool's")
            .setContentText("Próba-értesítés.")
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ID_TEST, notification)
        return true
    }
}
