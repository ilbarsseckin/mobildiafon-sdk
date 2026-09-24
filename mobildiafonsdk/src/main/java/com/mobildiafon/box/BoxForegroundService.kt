package com.mobildiafon.box

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Kalıcı foreground service — box kapı-servisi socket'ini (BoxDoorService) canlı tutar.
 *
 * Amaç: Android arka planda süreci öldürmesin. Böylece backend yeniden başlasa bile
 * box socket'i kopmaz / saniyeler içinde yeniden bağlanıp `box:register` yollar; QR
 * çift-kamera ve kapı-görüntüle her zaman hazır olur.
 *
 * START_STICKY: süreç öldürülürse sistem servisi yeniden başlatır (Application.onCreate
 * çağrılıp DiafonBox.init/activate yapıldıktan sonra beginDoorSocket tekrar bağlanır).
 */
class BoxForegroundService : Service() {

    companion object {
        private const val TAG = "DiafonBoxFgSvc"
        private const val CH_ID = "diafonbox_door"
        private const val NOTIF_ID = 4711

        fun start(context: Context) {
            val i = Intent(context, BoxForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
                else context.startService(i)
            } catch (e: Exception) { Log.e(TAG, "start", e) }
        }

        fun stop(context: Context) {
            try { context.stopService(Intent(context, BoxForegroundService::class.java)) } catch (_: Exception) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        try { DiafonBox.beginDoorSocket(applicationContext) } catch (e: Exception) { Log.e(TAG, "beginDoorSocket", e) }
        return START_STICKY
    }

    override fun onDestroy() {
        try { DiafonBox.endDoorSocket() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CH_ID) == null) {
                val ch = NotificationChannel(CH_ID, "DiafonBox Kapı Servisi", NotificationManager.IMPORTANCE_MIN)
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(): Notification {
        val icon = applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_menu_camera
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CH_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("DiafonBox aktif")
            .setContentText("Kapı görüntü servisi çalışıyor")
            .setSmallIcon(icon)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat() {
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) { Log.e(TAG, "startForeground", e) }
    }
}
