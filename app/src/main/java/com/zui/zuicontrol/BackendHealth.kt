package com.zui.zuicontrol

/** Pure presentation facts from one existing state response. No polling or persistent history. */
object BackendHealth {
    /** A new collector sample invalidates a state snapshot taken while it was paused. */
    fun collectorResumed(text:String,previousSampleTime:Long,nextSampleTime:Long):Boolean {
        if(nextSampleTime<=0 || nextSampleTime<=previousSampleTime)return false
        return text.lineSequence().any { it=="screenInteractive=false" || it=="monitorActive=false" || it=="monitorTimer=false" }
    }
    enum class State { OK, DEGRADED, FAILED, UNKNOWN }
    data class Component(val component: String,val state: State,val reason: String,val facts:List<String> = emptyList())
    fun components(text: String): List<Component> {
        val values=text.lineSequence().mapNotNull { val i=it.indexOf('=');if(i<1)null else it.substring(0,i) to it.substring(i+1) }.toMap()
        fun raw(key:String)=values[key] ?: "未提供"
        fun none(key:String)=values[key]?.takeUnless{it.isEmpty()||it=="none"} ?: if(key in values)"无" else "未提供"
        fun flag(key:String,on:String,off:String)=when(values[key]){"1","true"->on;"0","false"->off;else->"未提供"}
        fun service(key:String)=when(values[key]){"running"->"运行";"stopped"->"已停止";else->"未提供"}
        fun schedulerError(value:String)=when(value){
            "ok"->"正常";"none",""->"无";"invalid_scheduler_active"->"调度开关状态异常"
            "uperf_fail_safe"->"Uperf 已进入故障保护";"uperf_stopped_while_active"->"Uperf 在调度启用时停止"
            "invalid_uperf_mode"->"Uperf 档位状态异常";"invalid_zuiopt_failure"->"ZUIopt 故障状态异常"
            "zuiopt_not_stopped_in_failsafe"->"ZUIopt 故障保护未完成停机";"zuiopt_not_running_while_active"->"ZUIopt 在调度启用时未运行"
            "zui_scheduler_running_while_inactive"->"Owner 关闭调度后服务仍在运行";else->value
        }
        fun item(name:String,state:State,reason:String="",facts:List<String> = emptyList())=Component(name,state,reason.take(160),facts)
        val names=listOf("ZuiControlService","Uperf","ZUIopt","GPU控制","监视服务")
        if(values["systemServiceAlive"]!="true")return names.mapIndexed { i,name -> item(name,if(i==0&&values["systemServiceAlive"]=="false")State.FAILED else State.UNKNOWN,"SERVICE_UNAVAILABLE") }
        val active=values["schedulerActive"];val health=values["schedulerHealth"]
        fun scheduler(name:String,prefix:String):Component {
            val current=health?.takeUnless{it=="ok"}.orEmpty()
            val relevant=current.startsWith(prefix+"_")||current in setOf("invalid_scheduler_active","zui_scheduler_running_while_inactive")||(prefix=="uperf"&&current=="invalid_uperf_mode")
            val state:State;val reason:String
            when {
                values[prefix+"FailSafe"]=="1"||(prefix=="zuiopt"&&values["threadManagerState"]=="android_default_failsafe")->{state=State.DEGRADED;reason="FAILSAFE"}
                active !in setOf("0","1")||health==null||values[prefix+"FailSafe"]!="0"->{state=State.UNKNOWN;reason="STATE_UNAVAILABLE"}
                relevant->{state=State.DEGRADED;reason=current}
                active=="0"&&values[prefix+"ServiceState"]=="stopped"->{state=State.OK;reason="DISABLED_BY_OWNER"}
                active=="0"&&values[prefix+"ServiceState"]=="running"->{state=State.DEGRADED;reason="RUNNING_WHILE_DISABLED"}
                values[prefix+"ServiceState"]=="stopped"->{state=State.FAILED;reason="STOPPED_WHILE_ACTIVE"}
                values[prefix+"ServiceState"]!="running"->{state=State.UNKNOWN;reason="STATE_UNAVAILABLE"}
                prefix=="uperf"&&values["uperfMode"] !in setOf("powersave","balance","performance","fast")->{state=State.UNKNOWN;reason="MODE_UNAVAILABLE"}
                prefix=="zuiopt"&&values["threadManagerState"]==null->{state=State.UNKNOWN;reason="OWNERSHIP_UNAVAILABLE"}
                prefix=="zuiopt"&&values["threadManagerState"]!="zuiopt_active"->{state=State.DEGRADED;reason="OWNERSHIP_UNHEALTHY"}
                else->{state=State.OK;reason=""}
            }
            val detail=if(prefix=="uperf")"当前档位："+when(values["uperfMode"]){"powersave"->"省电";"balance"->"均衡";"performance"->"性能";"fast"->"极速";else->"未提供"}
                else "线程管理："+when(values["threadManagerState"]){"zuiopt_active"->"ZUIopt 已接管";"android_default_failsafe"->"Android 默认调度（故障保护）";"inactive_or_unhealthy"->if(active=="0")"Owner 已关闭" else "未接管或异常";else->"未提供"}
            return item(name,state,reason,listOf("服务："+service(prefix+"ServiceState"),"调度开关："+flag("schedulerActive","启用","Owner 已关闭"),detail,"故障保护："+flag(prefix+"FailSafe","已触发","未触发"),"当前调度健康："+schedulerError(health ?: "未提供"),"最近调度错误（历史）："+schedulerError(none("lastSchedulerError"))))
        }
        val gpu=item(names[3],when {
            values["gpuRuntime"]=="DEGRADED_FAIL_SAFE"||values["gpuFailSafe"]=="true"->State.DEGRADED
            values["gpuRuntime"]=="READY"&&values["gpuFailSafe"]=="false"->State.OK
            else->State.UNKNOWN
        },if(values["gpuRuntime"]=="DEGRADED_FAIL_SAFE")none("gpuLastError") else "",listOf("运行状态："+raw("gpuRuntime"),"故障保护："+flag("gpuFailSafe","已触发","未触发"),"控制通道："+raw("gpuTransport"),"最近错误："+none("gpuLastError")))
        val monitorError=listOf(values["monitorError"],values["monitorFinalizeError"]).filterNotNull().firstOrNull { it.isNotEmpty()&&it!="none" }.orEmpty()
        val mode=values["monitorMode"];val interval=values["monitorIntervalMs"]?.toIntOrNull()
        val expectedInterval=if(mode=="0"&&values["monitorRecordState"]=="IDLE")5000 else 1000
        val monitor=item(names[4],when {
            monitorError.isNotEmpty()->State.DEGRADED
            mode !in setOf("0","1","2")||interval==null||values["screenInteractive"] !in setOf("true","false")->State.UNKNOWN
            values["screenInteractive"]=="true"&&(values["monitorActive"]=="false"||values["monitorTimer"]=="false")->State.DEGRADED
            values["screenInteractive"]=="true"&&(values["monitorActive"]!="true"||values["monitorTimer"]!="true")->State.UNKNOWN
            mode=="0"&&interval!=expectedInterval->State.DEGRADED
            mode in setOf("1","2")&&interval !in setOf(1000,5000)->State.DEGRADED
            else->State.OK
        },monitorError,listOf("模式："+when(mode){"0"->"OFF（正常后台采样）";"1"->"FULL";"2"->"LITE";else->"未提供"},"采样周期："+(interval?.let{"$it ms"} ?: "未提供"),"采集线程："+flag("monitorActive","运行","已暂停"),"采样定时器："+flag("monitorTimer","运行","已暂停"),"录制："+raw("monitorRecordState"),"最近错误："+none("monitorError"),"收尾错误："+none("monitorFinalizeError")))
        val serviceState=when {
            values["policyRecoveryRequired"]=="true"->State.FAILED
            none("policyRetentionError") !in setOf("无","未提供")->State.DEGRADED
            values["policyRecoveryRequired"]!="false"||values["policyRetentionError"]==null->State.UNKNOWN
            else->State.OK
        }
        val control=item(names[0],serviceState,if(serviceState==State.OK)"" else if(values["policyRecoveryRequired"]=="true")"POLICY_RECOVERY_REQUIRED" else "POLICY_STATE_UNAVAILABLE",listOf("系统服务：在线","策略恢复："+flag("policyRecoveryRequired","需要恢复","正常"),"策略代次："+raw("policyGeneration"),"持久化错误："+none("policyRetentionError")))
        return listOf(control,scheduler(names[1],"uperf"),scheduler(names[2],"zuiopt"),gpu,monitor)
    }
}
