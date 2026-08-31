package com.zui.zuicontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.RemoteViews

class ZuiControlQuickService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val refreshNotificationRunnable = Runnable { updateNotification(preferPending = false) }
    private val settlePendingRateRunnable = Runnable { updateNotification(preferPending = false) }
    private lateinit var notificationManager: NotificationManager
    private var stateObserver: ContentObserver? = null
    private var pendingRate: Int? = null
    private var pendingRateUntilMs = 0L

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        registerStateObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rate = when (intent?.action) {
            ZuiControlContract.ACTION_SET_60 -> 60
            ZuiControlContract.ACTION_SET_90 -> 90
            ZuiControlContract.ACTION_SET_120 -> 120
            ZuiControlContract.ACTION_SET_144 -> 144
            ZuiControlContract.ACTION_SET_165 -> 165
            else -> intent?.getIntExtra(ZuiControlContract.EXTRA_RATE, 0)?.takeIf {
                it in ZuiControlContract.rates
            }
        }
        if (rate != null) {
            val reply = ZuiControlClient.setCurrentSceneDisplayHz(rate)
            pendingRate = if (reply.ok) rate else null
            pendingRateUntilMs = if (reply.ok) {
                SystemClock.elapsedRealtime() + PENDING_RATE_TIMEOUT_MS
            } else {
                0L
            }
            handler.removeCallbacks(refreshNotificationRunnable)
            handler.removeCallbacks(settlePendingRateRunnable)
            updateNotification(if (reply.ok) rate else currentRate())
            handler.postDelayed(settlePendingRateRunnable, PENDING_RATE_TIMEOUT_MS + 80L)
        } else {
            updateNotification(preferPending = false)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stateObserver?.let { contentResolver.unregisterContentObserver(it) }
        stateObserver = null
        handler.removeCallbacks(refreshNotificationRunnable)
        handler.removeCallbacks(settlePendingRateRunnable)
        super.onDestroy()
    }

    private fun updateNotification(rateOverride: Int? = null, preferPending: Boolean = true) {
        if (!preferPending && rateOverride == null) {
            clearPendingRate()
        }
        notificationManager.notify(NOTIFICATION_ID, buildNotification(rateOverride, preferPending))
    }

    private fun buildNotification(rateOverride: Int? = null, preferPending: Boolean = true): Notification {
        val selectedRate = rateOverride
            ?: (if (preferPending) pendingDisplayRate() else null)
            ?: currentRate()
        val content = RemoteViews(packageName, R.layout.notification_zuicontrol).apply {
            setTextViewText(R.id.notification_title, "ZuiControl")
            bindRate(this, R.id.rate_60, 60, selectedRate, ZuiControlContract.ACTION_SET_60)
            bindRate(this, R.id.rate_90, 90, selectedRate, ZuiControlContract.ACTION_SET_90)
            bindRate(this, R.id.rate_120, 120, selectedRate, ZuiControlContract.ACTION_SET_120)
            bindRate(this, R.id.rate_144, 144, selectedRate, ZuiControlContract.ACTION_SET_144)
            bindRate(this, R.id.rate_165, 165, selectedRate, ZuiControlContract.ACTION_SET_165)
        }
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        builder
            .setSmallIcon(R.drawable.ic_stat_zuicontrol)
            .setContentTitle("ZuiControl")
            .setContentText("${selectedRate}Hz")
            .setContentIntent(openIntent)
            .setCustomContentView(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_LOW)
            .setDefaults(0)
            .setSound(null)
            .setVibrate(null)
        return builder.build()
    }

    private fun registerStateObserver() {
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                scheduleNotificationRefresh()
            }
        }
        stateObserver = observer
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(ZuiControlContract.KEY_ACTIVE_REFRESH),
            false,
            observer,
        )
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(ZuiControlContract.KEY_SCENE_EVENT_TEXT),
            false,
            observer,
        )
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(ZuiControlContract.KEY_STATUS_TEXT),
            false,
            observer,
        )
    }

    private fun scheduleNotificationRefresh() {
        handler.removeCallbacks(refreshNotificationRunnable)
        handler.postDelayed(refreshNotificationRunnable, NOTIFICATION_REFRESH_DEBOUNCE_MS)
    }

    private fun pendingDisplayRate(): Int? {
        val rate = pendingRate ?: return null
        if (SystemClock.elapsedRealtime() <= pendingRateUntilMs) {
            return rate
        }
        clearPendingRate()
        return null
    }

    private fun clearPendingRate() {
        pendingRate = null
        pendingRateUntilMs = 0L
    }

    private fun bindRate(
        views: RemoteViews,
        viewId: Int,
        rate: Int,
        selectedRate: Int,
        action: String,
    ) {
        val selected = rate == selectedRate
        views.setTextViewText(viewId, rate.toString())
        views.setInt(
            viewId,
            "setBackgroundResource",
            if (selected) R.drawable.notify_rate_selected else R.drawable.notify_rate_normal,
        )
        views.setTextColor(viewId, if (selected) COLOR_SELECTED_TEXT else COLOR_NORMAL_TEXT)
        views.setOnClickPendingIntent(
            viewId,
            PendingIntent.getService(
                this,
                rate,
                Intent(this, ZuiControlQuickService::class.java)
                    .setAction(action)
                    .putExtra(ZuiControlContract.EXTRA_RATE, rate),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
    }

    private fun currentRate(): Int {
        return ZuiControlClient.editableDisplayHz() ?: RefreshSceneController.currentRate(this)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ZuiControl refresh",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Per-app refresh quick controls"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        notificationManager.deleteNotificationChannel("zui_control_quick_v3")
        notificationManager.deleteNotificationChannel("zui_control_quick_v4")
        notificationManager.deleteNotificationChannel("zui_control_quick_v5")
        notificationManager.deleteNotificationChannel("zui_control_quick_v6")
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "zui_control_quick_v7"
        private const val NOTIFICATION_ID = 18701
        private const val PENDING_RATE_TIMEOUT_MS = 1600L
        private const val NOTIFICATION_REFRESH_DEBOUNCE_MS = 160L
        private const val COLOR_SELECTED_TEXT = 0xFFFFFFFF.toInt()
        private const val COLOR_NORMAL_TEXT = 0xFF1C222A.toInt()

        fun start(context: Context) {
            val intent = Intent(context, ZuiControlQuickService::class.java)
                .setAction(ZuiControlContract.ACTION_REFRESH_NOTIFICATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
