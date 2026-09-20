package com.zui.zuicontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.widget.RemoteViews
import android.widget.Toast

/** Event-driven controller over the existing scene, profiles and Monitor desired mode. */
class ZuiControlQuickService : Service() {
    private var monitor: PerformanceMonitor? = null
    private val handler = Handler(Looper.getMainLooper())
    private var commandInFlight = false
    private val update = Runnable { refreshNotification() }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { requestRefresh() }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "ZuiControl", NotificationManager.IMPORTANCE_LOW).apply {
                description = "监视器、刷新率与性能快捷控制"
                setSound(null, null); enableVibration(false); setShowBadge(false)
            })
        startForeground(ID, notification())
        listOf(ZuiControlContract.KEY_STATUS_TEXT, ZuiControlContract.KEY_UPERF_MODE,
            ZuiControlContract.KEY_UPERF_RULES_TEXT).forEach {
            contentResolver.registerContentObserver(Settings.System.getUriFor(it), false, observer)
        }
        monitor = PerformanceMonitor(this) { requestRefresh() }.also { it.start() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action.orEmpty()
        when {
            action == "com.zui.zuicontrol.MONITOR_CONNECT" -> monitor?.start()
            action == FULL -> {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "请先允许 ZuiControl 显示悬浮窗", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    monitor?.start()
                    if (monitor?.toggle("full")?.startsWith("ok=1") != true) errorToast("监视器不可用")
                }
            }
            action.startsWith(REFRESH) -> {
                val rate = action.removePrefix(REFRESH).toIntOrNull()
                if (rate in ZuiControlContract.rates) mutate {
                    // Same transaction as the accepted current-scene Refresh control.
                    val reply = ZuiControlClient.setCurrentSceneDisplayHz(rate!!)
                    check(reply.ok) { reply.text }
                }
            }
            action.startsWith(UPERF) -> {
                val mode = UperfMode.fromId(action.removePrefix(UPERF))
                if (mode != null) mutate {
                    // Read the accepted editable scene at this action, never shade focus or a cache.
                    val scene = ZuiControlClient.currentSceneText()
                    val pkg = ZuiControlClient.stateValue(scene, "editableScenePackage").orEmpty()
                    check(PackageNames.isValid(pkg)) { "当前没有可配置应用" }
                    val request = ZuiControlRequest.send(this, ZuiControlContract.CMD_SET_UPERF_APP,
                        pkg = pkg, mode = mode.id)
                    val ack = ZuiControlRequest.awaitTerminalAck(this, request)
                    check(ack.succeeded) { ack.detail }
                }
            }
        }
        requestRefresh()
        return START_STICKY
    }
    private fun mutate(block: () -> Unit) {
        if (commandInFlight) { errorToast("操作处理中"); return }
        commandInFlight = true
        Thread {
            val result = runCatching(block)
            handler.post {
                commandInFlight = false
                result.onFailure { errorToast(it.message ?: "设置失败") }
                requestRefresh()
            }
        }.start()
    }
    private fun errorToast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun requestRefresh() { handler.removeCallbacks(update); handler.postDelayed(update, 80) }
    private fun refreshNotification() = getSystemService(NotificationManager::class.java).notify(ID, notification())
    private fun setting(key: String) = Settings.System.getString(contentResolver, key).orEmpty()
    private fun pending(action: String, request: Int) = PendingIntent.getService(this, request,
        Intent(this, ZuiControlQuickService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun notification(): Notification {
        val scene = ZuiControlClient.currentSceneText()
        val pkg = ZuiControlClient.stateValue(scene, "editableScenePackage").orEmpty()
        val rate = ZuiControlClient.stateValue(scene, "editableDisplayHz")?.toIntOrNull() ?: 0
        val mode = UperfMode.resolve(setting(ZuiControlContract.KEY_UPERF_MODE),
            setting(ZuiControlContract.KEY_UPERF_RULES_TEXT), pkg)
        val desired = ZuiControlClient.stateValue(PerformanceMonitor.command("state"), "monitorMode") == "1"
        val enabled = PackageNames.isValid(pkg)
        val content = RemoteViews(packageName, R.layout.notification_zuicontrol).apply {
            setInt(R.id.monitor_toggle, "setBackgroundResource",
                if (desired) R.drawable.notify_rate_selected else R.drawable.notify_rate_normal)
            setInt(R.id.monitor_toggle, "setColorFilter", if (desired) android.graphics.Color.WHITE else getColor(R.color.ui_accent))
            setContentDescription(R.id.monitor_toggle, "监视器 ${if (desired) "开启" else "关闭"}")
            setOnClickPendingIntent(R.id.monitor_toggle, pending(FULL, 1))
            listOf(R.id.refresh_60, R.id.refresh_90, R.id.refresh_120, R.id.refresh_144, R.id.refresh_165)
                .zip(ZuiControlContract.rates).forEach { (id, value) ->
                    val selected = value == rate
                    setInt(id, "setBackgroundResource", if (selected) R.drawable.notify_rate_selected else R.drawable.notify_rate_normal)
                    setTextColor(id, if (selected) android.graphics.Color.WHITE else getColor(R.color.ui_text))
                    setBoolean(id, "setEnabled", enabled)
                    setContentDescription(id, "${value}Hz ${if (selected) "已选择" else "未选择"}")
                    setOnClickPendingIntent(id, pending(REFRESH + value, value))
                }
            listOf(R.id.mode_powersave, R.id.mode_balance, R.id.mode_performance, R.id.mode_fast)
                .zip(UperfMode.entries).forEach { (id, value) ->
                    setTextViewText(id, "${if (value == mode) "●" else "○"}\n${value.title.first()}")
                    setTextColor(id, getColor(value.color))
                    setBoolean(id, "setEnabled", enabled)
                    setContentDescription(id, "${value.title} ${if (value == mode) "已选择" else "未选择"}")
                    setOnClickPendingIntent(id, pending(UPERF + value.id, 200 + value.ordinal))
                }
        }
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_zuicontrol)
            .setContentTitle("ZuiControl").setCustomContentView(content).setOngoing(true)
            .setOnlyAlertOnce(true).setShowWhen(false).setLocalOnly(true)
            .setCategory(Notification.CATEGORY_SERVICE).build()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        monitor?.close(); monitor = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
    companion object {
        private const val ID = 18701
        private const val CHANNEL = "zui_control_monitor_v1"
        const val FULL = "com.zui.zuicontrol.MONITOR_FULL"
        private const val REFRESH = "com.zui.zuicontrol.REFRESH_"
        private const val UPERF = "com.zui.zuicontrol.UPERF_"
        fun start(context: Context) { context.startForegroundService(Intent(context, ZuiControlQuickService::class.java)) }
    }
}
