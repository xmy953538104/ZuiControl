package com.zui.zuicontrol

/** Pure presentation facts from one existing state response. No polling or persistent history. */
object BackendHealth {
    enum class State { OK, DEGRADED, FAILED, UNKNOWN }
    data class Component(val component: String,val state: State,val reason: String)
    fun components(text: String): List<Component> {
        val values=text.lineSequence().mapNotNull { val i=it.indexOf('=');if(i<1)null else it.substring(0,i) to it.substring(i+1) }.toMap()
        fun item(name: String,state: State,reason: String="")=Component(name,state,reason.take(160))
        val names=listOf("ZuiControlService","Uperf","ZUIopt","GPU控制","监视服务")
        if(values["systemServiceAlive"]!="true")return names.mapIndexed { i,name -> item(name,if(i==0&&values["systemServiceAlive"]=="false")State.FAILED else State.UNKNOWN,"SERVICE_UNAVAILABLE") }
        val disabled=values["schedulerActive"]=="0"
        fun scheduler(name: String,prefix: String): Component = when {
            values["${prefix}FailSafe"]=="1" -> item(name,State.DEGRADED,"FAILSAFE")
            disabled&&values["${prefix}ServiceState"]=="stopped" -> item(name,State.OK,"DISABLED_BY_OWNER")
            values["${prefix}ServiceState"]=="running"&&values["${prefix}FailSafe"]=="0" -> item(name,State.OK)
            values["${prefix}ServiceState"]=="stopped" -> item(name,State.FAILED,"STOPPED_WHILE_ACTIVE")
            else -> item(name,State.UNKNOWN,"STATE_UNAVAILABLE")
        }
        val gpu=when(values["gpuRuntime"]){"READY"->item(names[3],State.OK);"DEGRADED_FAIL_SAFE"->item(names[3],State.DEGRADED,values["gpuLastError"].orEmpty());else->item(names[3],State.UNKNOWN)}
        val monitorError=listOf(values["monitorError"],values["monitorFinalizeError"]).filterNotNull().firstOrNull { it.isNotEmpty()&&it!="none" }.orEmpty()
        val monitor=when {
            monitorError.isNotEmpty()->item(names[4],State.DEGRADED,monitorError)
            values["monitorMode"] in setOf("0","1","2")->item(names[4],State.OK)
            else->item(names[4],State.UNKNOWN)
        }
        return listOf(item(names[0],State.OK),scheduler(names[1],"uperf"),scheduler(names[2],"zuiopt"),gpu,monitor)
    }
}
