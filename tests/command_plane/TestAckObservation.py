"""A/B the exact production wait loop; replace only Binder, clock, sleep and preferences."""
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).parent))
from TestUtilityTruth import compile_run
ROOT=Path(__file__).resolve().parents[2];APP=ROOT/'app/src/main/java/com/zui/zuicontrol'
context='''package android.content
class Prefs {val values=mutableMapOf<String,String>();fun getString(k:String,d:String?):String?=values[k]?:d;fun edit()=Editor(this)
 class Editor(val p:Prefs){val pending=p.values.toMutableMap();fun putString(k:String,v:String):Editor{pending[k]=v;return this};fun clear():Editor{pending.clear();return this};fun commit():Boolean{p.values.clear();p.values.putAll(pending);return true}}}
class Context {val prefs=Prefs();fun createDeviceProtectedStorageContext()=this;fun getSharedPreferences(n:String,m:Int)=prefs;companion object{const val MODE_PRIVATE=0}}
'''
clock='''package android.os
object SystemClock{var now=0L;fun elapsedRealtime()=now;fun elapsedRealtimeNanos()=now*1000000;fun sleep(ms:Long){now+=ms}}
'''
client='''package com.zui.zuicontrol
import android.os.SystemClock
object ZuiControlClient{data class Reply(val ok:Boolean,val text:String);var request="";var ready=0L;var queries=0;var kicks=0;var wrong=false
 fun sendPolicy(c:android.content.Context,a:String,p:String,s:String,mode:String="")="unused"
 fun stateValue(s:String,k:String)=s.lineSequence().firstOrNull{it.startsWith("$k=")}?.substringAfter('=')
 fun utility(action:String,arg:String):Reply{check(action=="submit");if(request.isNotEmpty())check(request==arg);request=arg;kicks++;return Reply(true,"ok=1")}
 fun utilityValue(action:String,id:String):String{check(action=="ack");queries++;val fields=request.split('|');
  return if(SystemClock.now<ready) "${if(wrong)"wrong" else fields[0]}|processing|${fields[1]}|waiting" else "${fields[0]}|done|${fields[1]}|ok=1"}
 fun reset(at:Long){request="";ready=at;queries=0;kicks=0;wrong=false;SystemClock.now=0}
}
fun main(args:Array<String>){
 val variant=args.single();val delays=mutableListOf<Long>();val queryCounts=mutableListOf<Int>()
 for(ready in 1L..1000L step 17){ZuiControlClient.reset(ready);val c=android.content.Context();val id=ZuiControlRequest.send(c,"policy");
  val ack=ZuiControlRequest.awaitTerminalAck(c,id,timeoutMs=2500);check(ack.succeeded&&ack.requestId==id&&c.prefs.values.isEmpty())
  delays+=SystemClock.now-ready;queryCounts+=ZuiControlClient.queries
 }
 ZuiControlClient.reset(120);ZuiControlClient.wrong=true;val c=android.content.Context();val id=ZuiControlRequest.send(c,"policy");
 check(ZuiControlRequest.awaitTerminalAck(c,id,timeoutMs=1000).requestId==id)
 ZuiControlClient.reset(Long.MAX_VALUE);val lost=android.content.Context();val pending=ZuiControlRequest.send(lost,"policy");val exact=lost.prefs.values.toMap()
 check(runCatching{ZuiControlRequest.awaitTerminalAck(lost,pending,timeoutMs=2500)}.isFailure)
 check(lost.prefs.values==exact&&ZuiControlClient.kicks>1);val before=ZuiControlClient.queries
 ZuiControlClient.ready=SystemClock.now;check(ZuiControlRequest.recoverPending(lost)==null&&lost.prefs.values.isEmpty())
 println("ACK_AB_$variant samples=${delays.size} median=${delays.sorted()[delays.size/2]} p95=${delays.sorted()[(delays.size*95+99)/100-1]} max=${delays.max()} totalQueries=${queryCounts.sum()} timeoutQueries=$before lostAckExactReplay=PASS wrongId=PASS")
}
'''
for variant in ['200','bounded50']:
 source=(APP/'ZuiControlRequest.kt').read_text('utf8').replace('Thread.sleep(','SystemClock.sleep(')
 if variant=='200':source=source.replace('ACK_FAST_POLL_MS = 50L','ACK_FAST_POLL_MS = 200L')
 files={'Context.kt':context,'Clock.kt':clock,'Log.kt':'package android.util\nobject Log{fun i(t:String,s:String)=0}',
        'Client.kt':client,'ZuiControlRequest.kt':source,'ZuiControlContract.kt':(APP/'ZuiControlContract.kt').read_text('utf8')}
 compile_run(files,'com.zui.zuicontrol.ClientKt',[variant])
