package com.zui.zuicontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Settings
import android.widget.RemoteViews
import android.widget.Toast

/** One persistent control surface. Its existence does not enable sampling. */
class ZuiControlQuickService : Service() {
    private var monitor: PerformanceMonitor? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"ZuiControl",NotificationManager.IMPORTANCE_LOW).apply {
            description="性能监视器与 FPS 显示";setSound(null,null);enableVibration(false);setShowBadge(false)
        })
        startForeground(18701,notification())
        monitor=PerformanceMonitor(this).also { it.start() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when(intent?.action) {
            "com.zui.zuicontrol.MONITOR_CONNECT" -> {
                if (monitor==null) monitor=PerformanceMonitor(this).also { it.start() }
                else monitor?.start()
            }
            FULL,FPS -> {
                if(!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this,"请先在系统设置允许 ZuiControl 显示悬浮窗",Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    monitor?.start()
                    val reply=monitor?.toggle(if(intent.action==FULL)"full" else "fps")
                    if(reply?.startsWith("ok=1")!=true) Toast.makeText(this,"监视器不可用",Toast.LENGTH_SHORT).show()
                }
            }
        }
        getSystemService(NotificationManager::class.java).notify(18701,notification())
        return START_STICKY
    }
    private fun notification(): Notification {
        val state=PerformanceMonitor.command("state")
        val mode=ZuiControlClient.stateValue(state,"monitorMode")?.toIntOrNull() ?: 0
        val content=RemoteViews(packageName,R.layout.notification_zuicontrol).apply {
            listOf(Triple(R.id.monitor_toggle,FULL,1),Triple(R.id.fps_toggle,FPS,2)).forEach { (id,action,value) ->
                setInt(id,"setBackgroundResource",if(mode==value)R.drawable.notify_rate_selected else R.drawable.notify_rate_normal)
                setTextColor(id,if(mode==value)0xFFFFFFFF.toInt() else 0xFF1C222A.toInt())
                setOnClickPendingIntent(id,PendingIntent.getService(this@ZuiControlQuickService,value,
                    Intent(this@ZuiControlQuickService,ZuiControlQuickService::class.java).setAction(action),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
        }
        return Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_stat_zuicontrol)
            .setContentTitle("ZuiControl").setCustomContentView(content).setOngoing(true)
            .setOnlyAlertOnce(true).setShowWhen(false).setLocalOnly(true)
            .setCategory(Notification.CATEGORY_SERVICE).build()
    }
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onDestroy(){monitor?.close();monitor=null;super.onDestroy()}
    companion object {
        private const val CHANNEL="zui_control_monitor_v1"
        const val FULL="com.zui.zuicontrol.MONITOR_FULL"
        const val FPS="com.zui.zuicontrol.MONITOR_FPS"
        fun start(context: Context){context.startForegroundService(Intent(context,ZuiControlQuickService::class.java))}
    }
}
