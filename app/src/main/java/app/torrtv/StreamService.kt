package app.torrtv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.torrtv.core.AppLog

/**
 * Държи процеса жив, докато върви стрийм (особено когато гледаш във външен плеър).
 * Пази и Wi-Fi от режима за пестене на енергия, който на много ТВ кутии реже скоростта на торента.
 */
class StreamService : Service() {

    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("stream", "Стрийминг", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = NotificationCompat.Builder(this, "stream")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("TorrTV")
            .setContentText("Върви стрийминг…")
            .setOngoing(true)
            .build()
        try {
            ServiceCompat.startForeground(
                this, 1, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
        } catch (e: Exception) {
            // ако системът откаже, стриймът пак ще върви, докато приложението е на преден план
        }
        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "torrtv:stream").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        }
        if (wifi == null) wifi = wifiLock()
        return START_NOT_STICKY
    }

    /**
     * Wi-Fi на пълна скорост, докато се гледа. Android 14+ сам сменя този режим с по-пестелив,
     * който действа само докато TorrTV е на екрана (вграденият плеър). При кабел няма ефект.
     * Ако нещо не стане, гледането продължава както досега.
     */
    private fun wifiLock(): WifiManager.WifiLock? = try {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm == null) {
            null
        } else {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 34) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wm.createWifiLock(mode, "torrtv:stream").apply {
                setReferenceCounted(false)
                acquire()
            }.also { AppLog.d("stream", "Wi-Fi: режим пълна скорост за гледането") }
        }
    } catch (e: Exception) {
        AppLog.d("stream", "Wi-Fi: режимът не се включи (${e.javaClass.simpleName})")
        null
    }

    override fun onDestroy() {
        try {
            wake?.release()
        } catch (e: Exception) {
        }
        wake = null
        try {
            wifi?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        wifi = null
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, StreamService::class.java))
            } catch (e: Exception) {
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, StreamService::class.java))
            } catch (e: Exception) {
            }
        }
    }
}
