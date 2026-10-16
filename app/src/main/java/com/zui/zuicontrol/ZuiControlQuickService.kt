package com.zui.zuicontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
    private val operations=QuickControlState()
    private var destroyed=false
    private var acknowledgedMonitorMode:Boolean?=null
    private var monitorAcknowledgedAt=0L
    private var reconciling=false
    private var refreshPosted = false
    private var controlsDirty = true
    private var controls: NotificationQuickControlHelper.Snapshot? = null
    private var lastRendered: NotificationQuickControlHelper.Snapshot? = null
    private val eligibility=mutableMapOf<String,Boolean>()
    private val eligibilityLoading=mutableSetOf<String>()
    private val packagesChanged:()->Unit={eligibility.clear();requestRefresh()}
    private var notificationPublishes = 0L
    private val update = Runnable { refreshPosted = false; refreshNotification() }
    private var quietC = -1.0
    private var powerW = Double.NaN
    private var readingTime = 0L
    private var readingTtl = 3500L
    private var lastReadingPublish = 0L
    private val readingUpdate = Runnable { lastReadingPublish = SystemClock.elapsedRealtime(); requestRefresh(false) }
    // A one-shot expiry only; it does not read a sensor or wake a sleeping device.
    private val readingExpiry = Runnable { acceptReading(-1.0, Double.NaN, 0L, 3500L) }
    private val controlsChanged: () -> Unit = { requestRefresh();reconcileVerifying() }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "ZuiControl", NotificationManager.IMPORTANCE_LOW).apply {
                description = "监视器、刷新率与性能快捷控制"
                setSound(null, null); enableVibration(false); setShowBadge(false)
            })
        startForeground(ID, renderNotification(snapshot()))
        ControlsState.observe(controlsChanged)
        FrontendPackages.observe(applicationContext,packagesChanged)
        monitor = PerformanceMonitor(this, onReading = ::acceptReading) { requestRefresh() }.also { it.start() }
        recoverControl()
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
                    val current=snapshot()
                    val target=monitorTarget()
                    val pending=operations.presentation("monitor",target)
                    if(pending?.phase !in listOf(QuickControlState.Phase.PENDING,QuickControlState.Phase.VERIFYING))
                        offer("monitor",target,(!current.isFloatActive).toString(),current.isFloatActive.toString())
                    else reconcileVerifying()
                }
            }
            action.startsWith(REFRESH) -> {
                val rate = action.removePrefix(REFRESH).toIntOrNull()
                val current=snapshot()
                if(rate in ZuiControlContract.rates && current.refreshEnabled)
                    offer("refresh",sceneTarget(ControlsState.snapshot),rate.toString(),current.currentHz.toString())
            }
            action.startsWith(UPERF) -> {
                val mode = UperfMode.fromId(action.removePrefix(UPERF))
                val current=snapshot()
                if(mode!=null && current.uperfEnabled)
                    offer("uperf",sceneTarget(ControlsState.snapshot),mode.id,current.currentMode.id)
            }
        }
        requestRefresh()
        return START_STICKY
    }
    private fun monitorTarget()=QuickControlState.Target(ZuiControlClient.currentUserId(),"","GLOBAL",0)
    private fun sceneTarget(scene:String)=QuickControlState.Target(ZuiControlClient.currentUserId(),
        ZuiControlClient.stateValue(scene,"editableScenePackage").orEmpty(),"FOREGROUND",
        ZuiControlClient.stateValue(scene,"policySceneGeneration")?.toLongOrNull() ?: -1)
    private fun offer(control:String,target:QuickControlState.Target,value:String,confirmed:String){
        val trace=QuickControlTrace.begin(if(control=="refresh")"Refresh" else if(control=="uperf")"Uperf" else "Monitor",value)
        QuickControlTrace.target(trace,target.pkg)
        OwnerRenderTrace.event("QUICK_INTENT","trace=$trace;control=$control;value=$value;user=${target.user};package=${target.pkg};scope=${target.scope};scene=${target.scene}")
        val operation=operations.offer(control,target,value,confirmed,trace)
        if(operation==null)reconcileVerifying()
        requestRefresh(false)
        if(operation!=null)execute(operation)
    }
    private fun execute(operation:QuickControlState.Operation){
        FrontendTransport.commands.execute {
            if(!operations.dispatch(operation))return@execute
            QuickControlTrace.mark(operation.trace,"dequeued")
            var phase=QuickControlState.Phase.FAILED
            var detail=""
            var modeReply:Boolean?=null
            var monitorIssued=false
            var definitiveRejection=false
            val started=SystemClock.elapsedRealtime()
            try{
                check(operation.target.user==ZuiControlClient.currentUserId()){"用户已变化"}
                if(operation.control=="monitor"){
                    monitorIssued=true
                    val reply=PerformanceMonitor.command(if(operation.value=="true")"full" else "off")
                    definitiveRejection=reply.startsWith("ok=0")
                    check(reply.startsWith("ok=1")){reply}
                    modeReply=ZuiControlClient.stateValue(reply,"monitorMode")=="1"
                    check(modeReply.toString()==operation.value){"监视器状态已变化"}
                    phase=QuickControlState.Phase.CONFIRMED
                    detail="DIRECT_MONITOR_REPLY"
                }else{
                    val scene=ZuiControlClient.currentSceneText()
                    check(sceneTarget(scene)==operation.target){"前台场景已变化，未派发旧目标"}
                    if(operation.control=="uperf")check(ZuiControlClient.stateValue(scene,"editableSceneIsHome")=="true" ||
                        UperfAppPolicy.isConfigurable(packageManager,operation.target.pkg)){"当前应用不可配置"}
                    val id=ZuiControlClient.sendPolicy(this,
                        if(operation.control=="refresh")"refresh" else "mode",operation.target.pkg,"FOREGROUND",
                        value=if(operation.control=="refresh")operation.value.toInt() else 0,
                        mode=if(operation.control=="uperf")operation.value else "",scene=scene)
                    operations.identify(operation,id)
                    QuickControlTrace.mark(operation.trace,"dispatch",id)
                    val ack=ZuiControlRequest.awaitTerminalAck(this,id)
                    phase=if(ack.succeeded)QuickControlState.Phase.CONFIRMED else QuickControlState.Phase.FAILED
                    detail=ack.detail
                    QuickControlTrace.mark(operation.trace,"terminalAck",ack.state)
                }
            }catch(error:Exception){
                detail=error.message.orEmpty()
                if(operation.requestId.isNotEmpty() || monitorIssued && !definitiveRejection)phase=QuickControlState.Phase.VERIFYING
            }
            finish(operation,phase,detail,modeReply,started)
        }
    }
    private fun finish(operation:QuickControlState.Operation,phase:QuickControlState.Phase,detail:String,
                       modeReply:Boolean?=null,started:Long=0){
        handler.post{
            if(destroyed)return@post
            if(modeReply!=null){acknowledgedMonitorMode=modeReply;monitorAcknowledgedAt=started}
            val next=operations.finish(operation,phase,detail)
            OwnerRenderTrace.event("QUICK_TERMINAL","trace=${operation.trace};control=${operation.control};id=${operation.requestId};phase=$phase;sequence=${operation.sequence}")
            if(phase==QuickControlState.Phase.FAILED)errorToast(detail.take(120))
            requestRefresh()
            if(next!=null)execute(next)
        }
    }
    private fun recoverControl(){
        FrontendTransport.commands.execute{
            val pending=ZuiControlRequest.pendingIdentity(this) ?: return@execute
            val parsed=runCatching{
                val fields=pending.second.split('|',limit=5)
                check(fields[1]=="policy")
                org.json.JSONObject(java.util.Base64.getDecoder().decode(fields[4]).toString(Charsets.UTF_8))
            }.getOrNull() ?: return@execute
            val control=when(parsed.optString("action")){"refresh"->"refresh";"mode"->"uperf";else->return@execute}
            val target=QuickControlState.Target(parsed.getInt("userId"),parsed.getString("packageName"),parsed.getString("scope"),parsed.getLong("sceneGeneration"))
            val operation=operations.recover(control,target,if(control=="refresh")parsed.getInt("value").toString() else parsed.getString("mode"),pending.first)
            handler.post{if(!destroyed)requestRefresh(false)}
            try{
                val ack=ZuiControlRequest.awaitTerminalAck(this,pending.first)
                finish(operation,if(ack.succeeded)QuickControlState.Phase.CONFIRMED else QuickControlState.Phase.FAILED,ack.detail)
            }catch(error:Exception){
                finish(operation,QuickControlState.Phase.VERIFYING,error.message.orEmpty())
            }
        }
    }
    /** One exact read after an authority event or explicit tap; no periodic retry sampler. */
    private fun reconcileVerifying(){
        val waiting=operations.verifying()
        if(reconciling || waiting.isEmpty())return
        reconciling=true
        FrontendTransport.commands.execute{
            try{for(operation in waiting){
                if(operation.target.user!=ZuiControlClient.currentUserId())continue
                if(operation.requestId.isNotEmpty()){
                    val ack=ZuiControlRequest.parseAck(ZuiControlClient.utilityValue("ack",operation.requestId))
                    if(ack?.isTerminal==true && ack.requestId==operation.requestId && ack.command=="policy")
                        finish(operation,if(ack.succeeded)QuickControlState.Phase.CONFIRMED else QuickControlState.Phase.FAILED,ack.detail)
                }else if(operation.control=="monitor"){
                    val reply=PerformanceMonitor.command("state")
                    if(reply.startsWith("ok=1"))finish(operation,QuickControlState.Phase.UNKNOWN,
                        "当前状态已核对；上次命令结果仍未知",ZuiControlClient.stateValue(reply,"monitorMode")=="1",SystemClock.elapsedRealtime())
                }
            }}finally{handler.post{reconciling=false}}
        }
    }
    private fun errorToast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun acceptReading(quiet: Double, power: Double, elapsed: Long, ttl: Long) {
        val now = SystemClock.elapsedRealtime()
        if(elapsed>=monitorAcknowledgedAt && elapsed>0)acknowledgedMonitorMode=null
        readingTtl = ttl.coerceIn(3500L, 7500L)
        val fresh = elapsed > 0 && now - elapsed in 0..readingTtl
        val nextQuiet = if (fresh && quiet.isFinite() && quiet > 0) quiet else -1.0
        val nextPower = if (fresh && power.isFinite()) power else Double.NaN
        val invalidated = NotificationQuickControlHelper.availabilityChanged(quietC, nextQuiet, powerW, nextPower)
        val powerChanged = LivePower.number(powerW) != LivePower.number(nextPower)
        val changed = NotificationQuickControlHelper.metricNumber(quietC) != NotificationQuickControlHelper.metricNumber(nextQuiet) || powerChanged
        quietC = nextQuiet; powerW = nextPower; readingTime = if (fresh) elapsed else 0L
        handler.removeCallbacks(readingExpiry)
        if (fresh) handler.postDelayed(readingExpiry, (elapsed + readingTtl + 1L - now).coerceAtLeast(1))
        if (!changed) return
        if (invalidated || powerChanged) {
            handler.removeCallbacks(readingUpdate); requestRefresh(false)
        } else if (!handler.hasCallbacks(readingUpdate)) {
            handler.postDelayed(readingUpdate, (lastReadingPublish + 1000L - now).coerceAtLeast(0))
        }
    }
    private fun requestRefresh(controlsChanged: Boolean = true) {
        controlsDirty = controlsDirty || controlsChanged
        if (refreshPosted) return
        refreshPosted = true
        handler.post(update)
    }
    private fun refreshNotification() {
        val state = snapshot()
        val displayed = state.copy(
            quietC = NotificationQuickControlHelper.metricNumber(state.quietC).toDoubleOrNull() ?: -1.0,
            powerW = LivePower.number(state.powerW).toDoubleOrNull() ?: Double.NaN)
        if (displayed == lastRendered) return
        lastRendered = displayed
        val trace = QuickControlTrace.observed(state.pkg, state.currentHz, state.currentMode.id, "T2",state.isFloatActive)
        val content = renderNotification(state)
        getSystemService(NotificationManager::class.java).notify(ID, content)
        notificationPublishes++
        listOfNotNull(state.refreshStatus,state.uperfStatus,state.monitorStatus).forEach{
            if(it.phase==QuickControlState.Phase.PENDING || it.phase==QuickControlState.Phase.VERIFYING)
                QuickControlTrace.mark(it.trace,"localFeedbackPosted","NotificationManager.notify_returned")
        }
        QuickControlTrace.mark(trace,"feedbackPosted","notify_returned")
        OwnerRenderTrace.event("QUICK_NOTIFICATION_POSTED","refresh=${state.refreshStatus?.phase};refreshId=${state.refreshStatus?.trace};uperf=${state.uperfStatus?.phase};uperfId=${state.uperfStatus?.trace};monitor=${state.monitorStatus?.phase};monitorId=${state.monitorStatus?.trace}")
        OwnerRenderTrace.event("POWER_NOTIFICATION_POSTED","elapsed=$readingTime;displayPower=${LivePower.number(powerW)}")
        QuickControlTrace.mark(trace,"T3","NotificationManager.notify_returned_logical_state")
    }
    private var pendingIntentCreations = 0L
    private val actionIntents = mutableMapOf<Pair<String, Int>, PendingIntent>()
    private fun pending(action: String, request: Int) = actionIntents.getOrPut(action to request) {
        pendingIntentCreations++
        PendingIntent.getService(this, request,
            Intent(this, ZuiControlQuickService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    private fun snapshot(): NotificationQuickControlHelper.Snapshot {
        if (controlsDirty || controls == null) {
            val scene = ControlsState.snapshot
            val pkg = ZuiControlClient.stateValue(scene, "editableScenePackage").orEmpty()
            val rate = ZuiControlClient.stateValue(scene, "editableDisplayHz")?.toIntOrNull() ?: 0
            val mode = UperfMode.fromId(ZuiControlClient.stateValue(scene, "editableUperfMode").orEmpty()) ?: UperfMode.BALANCE
            val enabled = PackageNames.isValid(pkg)
            val uperfEnabled = ZuiControlClient.stateValue(scene, "editableSceneIsHome") == "true" || eligibility[pkg]==true
            if(enabled && pkg !in eligibility && eligibilityLoading.add(pkg)) {
                val version=FrontendPackages.version
                FrontendTransport.reads.execute {
                    val qualified=UperfAppPolicy.isConfigurable(packageManager,pkg)
                    handler.post {
                        eligibilityLoading.remove(pkg)
                        if(version==FrontendPackages.version)eligibility[pkg]=qualified
                        requestRefresh()
                    }
                }
            }
            controls = NotificationQuickControlHelper.Snapshot(pkg, rate, mode, false, enabled, uperfEnabled)
            controlsDirty = false
        }
        val fresh = readingTime > 0 && SystemClock.elapsedRealtime() - readingTime in 0..readingTtl
        // monitorMode comes from the existing authenticated producer snapshot, never a second state query.
        val scene=sceneTarget(ControlsState.snapshot)
        return controls!!.copy(isFloatActive = acknowledgedMonitorMode ?: (monitor?.desiredFull() == true),
            quietC = if (fresh) quietC else -1.0, powerW = if (fresh) powerW else Double.NaN,
            dark=resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES,
            refreshStatus=operations.presentation("refresh",scene),uperfStatus=operations.presentation("uperf",scene),
            monitorStatus=operations.presentation("monitor",monitorTarget()))
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
        writer.println("quickCommandLane=PROCESS_SHARED refreshPosted=$refreshPosted notificationPublishes=$notificationPublishes pendingIntentCreations=$pendingIntentCreations")
        monitor?.dump(writer); QuickControlTrace.dump(writer)
    }
    override fun onDestroy() {
        destroyed=true
        ControlsState.remove(controlsChanged)
        FrontendPackages.remove(packagesChanged)
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
