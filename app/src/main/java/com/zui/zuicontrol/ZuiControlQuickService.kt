package com.zui.zuicontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.RemoteViews
import android.widget.Toast
import java.io.FileDescriptor
import java.io.PrintWriter

/** Event-driven controller over the existing scene, profiles and Monitor desired mode. */
class ZuiControlQuickService : Service() {
    private var monitor: PerformanceMonitor? = null
    private val handler = Handler(Looper.getMainLooper())
    private var commandInFlight = false
    private var refreshPosted = false
    private val update = Runnable { refreshPosted = false; refreshNotification() }
    private var quietC = -1.0
    private var powerW = -1.0
    private var readingTime = 0L
    private var readingTtl = 3500L
    private var lastReadingPublish = 0L
    private val readingUpdate = Runnable { lastReadingPublish = SystemClock.elapsedRealtime(); requestRefresh() }
    // A one-shot expiry only; it does not read a sensor or wake a sleeping device.
    private val readingExpiry = Runnable { acceptReading(-1.0, -1.0, 0L, 3500L) }
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
        startForeground(ID, renderNotification(snapshot()))
        listOf(ZuiControlContract.KEY_STATUS_TEXT, ZuiControlContract.KEY_UPERF_MODE,
            ZuiControlContract.KEY_UPERF_RULES_TEXT).forEach {
            contentResolver.registerContentObserver(Settings.System.getUriFor(it), false, observer)
        }
        monitor = PerformanceMonitor(this, onReading = ::acceptReading) { requestRefresh() }.also { it.start() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action.orEmpty()
        when {
            action == "com.zui.zuicontrol.SETTINGS_RESTORED" -> monitor?.reloadPreferences()
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
                val trace = QuickControlTrace.begin("Refresh",rate.toString())
                if (rate in ZuiControlContract.rates) mutate {
                    val scene = ZuiControlClient.currentSceneText()
                    QuickControlTrace.target(trace,ZuiControlClient.stateValue(scene,"editableScenePackage").orEmpty())
                    // Same transaction as the accepted current-scene Refresh control.
                    val reply = ZuiControlClient.setCurrentSceneDisplayHz(this, rate!!, scene)
                    check(reply.ok) { reply.text }
                    QuickControlTrace.mark(trace,"T2","profile_transaction_committed")
                }
            }
            action.startsWith(UPERF) -> {
                val mode = UperfMode.fromId(action.removePrefix(UPERF))
                val trace = QuickControlTrace.begin("Uperf",mode?.id.orEmpty())
                if (mode != null) mutate {
                    // Read the accepted editable scene at this action, never shade focus or a cache.
                    val scene = ZuiControlClient.currentSceneText()
                    val pkg = ZuiControlClient.stateValue(scene, "editableScenePackage").orEmpty()
                    QuickControlTrace.target(trace,pkg)
                    check(ZuiControlClient.stateValue(scene, "editableSceneIsHome") == "true" || UperfAppPolicy.isConfigurable(packageManager, pkg)) { "当前应用不支持性能配置" }
                    val request = ZuiControlClient.sendPolicy(this, "mode", pkg, "FOREGROUND", mode = mode.id, scene = scene)
                    QuickControlTrace.mark(trace,"dispatch",request)
                    val ack = ZuiControlRequest.awaitTerminalAck(this, request)
                    check(ack.succeeded) { ack.detail }
                    QuickControlTrace.mark(trace,"terminalAck",ack.detail)
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
    private fun acceptReading(quiet: Double, power: Double, elapsed: Long, ttl: Long) {
        val now = SystemClock.elapsedRealtime()
        readingTtl = ttl.coerceIn(3500L, 7500L)
        val fresh = elapsed > 0 && now - elapsed in 0..readingTtl
        val nextQuiet = if (fresh && quiet.isFinite() && quiet > 0) quiet else -1.0
        val nextPower = if (fresh && power.isFinite() && power > 0) power else -1.0
        val invalidated = (quietC > 0 && nextQuiet < 0) || (powerW > 0 && nextPower < 0)
        val changed = quietC != nextQuiet || powerW != nextPower
        quietC = nextQuiet; powerW = nextPower; readingTime = if (fresh) elapsed else 0L
        handler.removeCallbacks(readingExpiry)
        if (fresh) handler.postDelayed(readingExpiry, (elapsed + readingTtl + 1L - now).coerceAtLeast(1))
        if (!changed) return
        if (invalidated) {
            handler.removeCallbacks(readingUpdate); requestRefresh()
        } else if (!handler.hasCallbacks(readingUpdate)) {
            handler.postDelayed(readingUpdate, (lastReadingPublish + 2000L - now).coerceAtLeast(0))
        }
    }
    private fun requestRefresh() {
        if (refreshPosted) return
        refreshPosted = true
        handler.post(update)
    }
    private fun refreshNotification() {
        val state = snapshot()
        val trace = QuickControlTrace.observed(state.pkg, state.currentHz, state.currentMode.id, "T2")
        val content = renderNotification(state)
        getSystemService(NotificationManager::class.java).notify(ID, content)
        QuickControlTrace.mark(trace,"T3","NotificationManager.notify_returned_logical_state")
    }
    private fun setting(key: String) = Settings.System.getString(contentResolver, key).orEmpty()
    private fun pending(action: String, request: Int) = PendingIntent.getService(this, request,
        Intent(this, ZuiControlQuickService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun snapshot(): NotificationQuickControlHelper.Snapshot {
        val scene = ZuiControlClient.currentSceneText()
        val pkg = ZuiControlClient.stateValue(scene, "editableScenePackage").orEmpty()
        val rate = ZuiControlClient.stateValue(scene, "editableDisplayHz")?.toIntOrNull() ?: 0
        val mode = UperfMode.resolve(setting(ZuiControlContract.KEY_UPERF_MODE),
            setting(ZuiControlContract.KEY_UPERF_RULES_TEXT), pkg)
        val desired = ZuiControlClient.stateValue(PerformanceMonitor.command("state"), "monitorMode") == "1"
        val enabled = PackageNames.isValid(pkg)
        val uperfEnabled = ZuiControlClient.stateValue(scene, "editableSceneIsHome") == "true" || UperfAppPolicy.isConfigurable(packageManager, pkg)
        val fresh = readingTime > 0 && SystemClock.elapsedRealtime() - readingTime in 0..readingTtl
        return NotificationQuickControlHelper.Snapshot(pkg, rate, mode, desired, enabled, uperfEnabled,
            if (fresh) quietC else -1.0, if (fresh) powerW else -1.0)
    }
    @Suppress("DEPRECATION")
    private fun renderNotification(snapshot: NotificationQuickControlHelper.Snapshot): Notification {
        val content = RemoteViews(packageName, R.layout.notification_quick_control)
        NotificationQuickControlHelper.updateRemoteViews(content, snapshot, pending(FULL, 1),
            { value -> pending(REFRESH + value, value) },
            { mode -> pending(UPERF + mode.id, 200 + mode.ordinal) })
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_zuicontrol)
            .setContentTitle("ZuiControl").setContent(content).setOngoing(true)
            .setOnlyAlertOnce(true).setShowWhen(false).setLocalOnly(true)
            .setCategory(Notification.CATEGORY_SERVICE).build()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        monitor?.environmentChanged()
        requestRefresh()
    }
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        args.firstOrNull { it.startsWith("--trace-seconds=") }?.substringAfter('=')?.toIntOrNull()?.let {
            QuickControlTrace.enable(it); monitor?.trace(it)
        }
        writer.println("quickCommandInFlight=$commandInFlight refreshPosted=$refreshPosted")
        monitor?.dump(writer); QuickControlTrace.dump(writer)
    }
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
