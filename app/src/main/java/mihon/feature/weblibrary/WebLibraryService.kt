package mihon.feature.weblibrary

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

/**
 * Keeps the process alive while the browser reads the library (type dataSync), with a Disconnect action. It does no
 * work itself: [WebLibraryHost] owns the connection and starts and stops it.
 */
class WebLibraryService : Service() {

    override fun onCreate() {
        super.onCreate()
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            appGraph.webLibraryHost.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        return START_NOT_STICKY
    }

    /** Android 15 limits dataSync services to 6 hours a day. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        appGraph.webLibraryHost.disconnect()
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun routedThroughVpn(): Boolean = try {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    } catch (_: Exception) {
        false
    }

    private fun goForeground() {
        val disconnect = PendingIntent.getService(
            this,
            0,
            Intent(this, WebLibraryService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = notificationBuilder(Notifications.CHANNEL_WEB_LIBRARY) {
            setSmallIcon(R.drawable.ic_mihon)
            setContentTitle(stringResource(MR.strings.web_library_notification))
            // A VPN app (e.g. AdGuard) that routes this app keeps the browser from reaching the phone on the Wi-Fi.
            val text = stringResource(
                if (routedThroughVpn()) MR.strings.web_library_vpn_hint else MR.strings.web_library_notification_text,
            )
            setContentText(text)
            setStyle(NotificationCompat.BigTextStyle().bigText(text))
            setOngoing(true)
            setShowWhen(false)
            setOnlyAlertOnce(true)
            setCategory(NotificationCompat.CATEGORY_SERVICE)
            open?.let(::setContentIntent)
            addAction(R.drawable.ic_close_24dp, stringResource(MR.strings.web_library_disconnect), disconnect)
        }.build()
        ServiceCompat.startForeground(
            this,
            Notifications.ID_WEB_LIBRARY,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    companion object {
        private const val ACTION_DISCONNECT = "mihon.feature.weblibrary.DISCONNECT"

        @Volatile private var running = false

        /** Best effort: Android refuses foreground services started from the background; the session still runs. */
        fun start(context: Context) {
            if (running) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, WebLibraryService::class.java))
                running = true
            } catch (error: Exception) {
                logcat(LogPriority.WARN, error) { "Web library service not started" }
            }
        }

        fun stop(context: Context) {
            if (!running) return
            running = false
            context.stopService(Intent(context, WebLibraryService::class.java))
        }
    }
}
