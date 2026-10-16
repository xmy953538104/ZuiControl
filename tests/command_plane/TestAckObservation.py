"""Exact callback wait loop: early/lost/wrong ACKs and real monitor wake, with platform boundaries only."""
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).parent))
from TestUtilityTruth import compile_run
ROOT=Path(__file__).resolve().parents[2];APP=ROOT/'app/src/main/java/com/zui/zuicontrol'
import sys
sys.path.insert(0,str(ROOT/'tests'))
from FrontendSource import read_frontend
context='''package android.content
class Prefs {val values=mutableMapOf<String,String>();fun getString(k:String,d:String?):String?=values[k]?:d;fun edit()=Editor(this)
 class Editor(val p:Prefs){val pending=p.values.toMutableMap();fun putString(k:String,v:String):Editor{pending[k]=v;return this};fun clear():Editor{pending.clear();return this};fun commit():Boolean{p.values.clear();p.values.putAll(pending);return true}}}
class Context {val prefs=Prefs();fun createDeviceProtectedStorageContext()=this;fun getSharedPreferences(n:String,m:Int)=prefs;companion object{const val MODE_PRIVATE=0}}
'''
clock='''package android.os
object SystemClock{@Volatile var now=0L;fun elapsedRealtime()=now;fun elapsedRealtimeNanos()=now*1000000;fun sleep(ms:Long){now+=ms}}
'''
client='''package com.zui.zuicontrol
import android.os.SystemClock
object ZuiControlClient{data class Reply(val ok:Boolean,val text:String);var request="";var ready=0L;var queries=0;var kicks=0;var wrong=false
 var callbacks=true;var eventError="";var delivered=false;var accepted=false;var terminal="done"
 fun sendPolicy(c:android.content.Context,a:String,p:String,s:String,mode:String="")="unused"
 fun stateValue(s:String,k:String)=s.lineSequence().firstOrNull{it.startsWith("$k=")}?.substringAfter('=')
 fun emit(){val f=request.split('|');val id=if(eventError=="id")"wrong" else f[0];val hash=if(eventError=="hash")"b".repeat(64) else ZuiControlRequest.sha256(request)
  val cmd=if(eventError=="command")"wrong" else f[1];val state=if(eventError=="state")"unknown" else terminal
  accepted=ZuiControlRequest.acceptAckCallback(id,hash,"$id|$state|$cmd|ok=1")}
 fun waitForAck(ms:Long){val end=SystemClock.now+ms
  if(callbacks&&!delivered&&ready<=end){SystemClock.now=maxOf(SystemClock.now,ready);delivered=true;emit()}else SystemClock.now=end}
 fun utility(action:String,arg:String):Reply{check(action=="submit");if(request.isNotEmpty())check(request==arg);request=arg;kicks++;if(callbacks&&ready==0L&&!delivered){delivered=true;emit()};return Reply(true,"ok=1")}
 fun utilityValue(action:String,id:String):String{check(action=="ack");queries++;val fields=request.split('|');
  return if(SystemClock.now<ready) "${if(wrong)"wrong" else fields[0]}|processing|${fields[1]}|waiting" else "${fields[0]}|$terminal|${fields[1]}|ok=1"}
 fun reset(at:Long){request="";ready=at;queries=0;kicks=0;wrong=false;callbacks=true;eventError="";delivered=false;accepted=false;terminal="done";SystemClock.now=0}
}
fun main(args:Array<String>){
 val variant=args.single();val delays=mutableListOf<Long>();val queryCounts=mutableListOf<Int>()
 if(variant=="real"){
  repeat(5){ZuiControlClient.reset(Long.MAX_VALUE);val c=android.content.Context();val id=ZuiControlRequest.send(c,"policy")
   val callback=Thread{Thread.sleep(30);SystemClock.now=30;ZuiControlClient.emit()};callback.start()
   check(ZuiControlRequest.awaitTerminalAck(c,id,timeoutMs=2500).succeeded);callback.join()
   check(ZuiControlClient.queries==1&&c.prefs.values.isEmpty()&&ZuiControlClient.accepted)}
  println("ACK_REAL_OBJECT_WAIT_CALLBACK_WAKE=PASS; DEVICE_LATENCY=NOT_CLAIMED");return
 }
 for(ready in 1L..1000L step 17){ZuiControlClient.reset(ready);ZuiControlClient.callbacks=variant=="callback";val c=android.content.Context();val id=ZuiControlRequest.send(c,"policy");
  val ack=ZuiControlRequest.awaitTerminalAck(c,id,timeoutMs=2500);check(ack.succeeded&&ack.requestId==id&&c.prefs.values.isEmpty())
  delays+=SystemClock.now-ready;queryCounts+=ZuiControlClient.queries
  check(ZuiControlClient.queries==if(variant=="callback")1 else 2)
 }
 for(error in listOf("id","hash","command","state")){
  ZuiControlClient.reset(20);ZuiControlClient.eventError=error;val c=android.content.Context();val id=ZuiControlRequest.send(c,"policy")
  check(ZuiControlRequest.awaitTerminalAck(c,id,timeoutMs=2500).succeeded)
  check(!ZuiControlClient.accepted&&ZuiControlClient.queries==2&&c.prefs.values.isEmpty())
 }
 ZuiControlClient.reset(0);val early=android.content.Context();val earlyId=ZuiControlRequest.send(early,"policy")
 check(ZuiControlRequest.awaitTerminalAck(early,earlyId,timeoutMs=2500).succeeded&&ZuiControlClient.queries==0)
 ZuiControlClient.reset(20);ZuiControlClient.terminal="failed";val failed=android.content.Context();val failedId=ZuiControlRequest.send(failed,"policy")
 check(!ZuiControlRequest.awaitTerminalAck(failed,failedId,timeoutMs=2500).succeeded&&failed.prefs.values.isEmpty())
 ZuiControlClient.reset(120);ZuiControlClient.wrong=true;val c=android.content.Context();val id=ZuiControlRequest.send(c,"policy");
 check(ZuiControlRequest.awaitTerminalAck(c,id,timeoutMs=1000).requestId==id)
 ZuiControlClient.reset(Long.MAX_VALUE);val lost=android.content.Context();val pending=ZuiControlRequest.send(lost,"policy");val exact=lost.prefs.values.toMap()
 check(runCatching{ZuiControlRequest.awaitTerminalAck(lost,pending,timeoutMs=2500)}.isFailure)
 check(lost.prefs.values==exact&&ZuiControlClient.kicks>1);val before=ZuiControlClient.queries
 ZuiControlClient.ready=SystemClock.now;check(ZuiControlRequest.recoverPending(lost)==null&&lost.prefs.values.isEmpty())
 println("ACK_CALLBACK_$variant samples=${delays.size} logicalMedian=${delays.sorted()[delays.size/2]} logicalMax=${delays.max()} totalQueries=${queryCounts.sum()} timeoutQueries=$before lostAckExactReplay=PASS wrongIdentity=PASS earlyCallback=PASS terminalFailure=PASS DEVICE_LATENCY=NOT_CLAIMED")
}
'''
for variant in ['callback','dropped','real']:
 source=read_frontend(APP/'ZuiControlRequest.kt')
 assert source.count('waiter.signal.wait(waitMs)')==1
 if variant!='real':source=source.replace('waiter.signal.wait(waitMs)','ZuiControlClient.waitForAck(waitMs)')
 files={'Context.kt':context,'Clock.kt':clock,'Log.kt':'package android.util\nobject Log{fun i(t:String,s:String)=0}',
        'Client.kt':client,'ZuiControlRequest.kt':source,'ZuiControlContract.kt':read_frontend(APP/'ZuiControlContract.kt')}
 compile_run(files,'com.zui.zuicontrol.ClientKt',[variant])
