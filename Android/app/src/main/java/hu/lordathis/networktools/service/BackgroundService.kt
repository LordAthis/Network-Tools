// Verzio: v0.1.0 - 2026-09-21
package hu.lordathis.networktools.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import hu.lordathis.networktools.notify.NotificationHelper

/**
 * Háttérben futás: előtér-szolgáltatás állandó (néma) értesítéssel, hogy az Android ne állítsa le az
 * appot, amikor a képernyőről lekerül. Egyelőre a KÉPESSÉG van meg (a folyamat életben marad);
 * a későbbi háttér-feladatok (pl. hálózat-figyelés) ide kerülnek.
 *
 * Be/ki: Beállítások > Háttérben futás. Az app indításakor, ha be volt kapcsolva, újraindul.
 */
class BackgroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationHelper.ensureBackgroundChannel(this)
        val notification = NotificationHelper.buildBackgroundNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationHelper.ID_BACKGROUND,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NotificationHelper.ID_BACKGROUND, notification)
        }
        return START_STICKY
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BackgroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BackgroundService::class.java))
        }
    }
}
