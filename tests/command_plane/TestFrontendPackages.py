"""Execute the metadata cache against counted PackageManager boundaries."""
from pathlib import Path
from TestUtilityTruth import compile_run

ROOT=Path(__file__).resolve().parents[2]
source=(ROOT/'app/src/main/java/com/zui/zuicontrol/FrontendPackages.kt').read_text('utf8')
files={
'FrontendPackages.kt':source,
'Content.kt':'''package android.content
import android.content.pm.PackageManager
open class Context(val packageManager:PackageManager){val applicationContext get()=this;val resources=Any()
 fun registerReceiver(r:BroadcastReceiver,f:IntentFilter){receiver=r};fun registerComponentCallbacks(c:ComponentCallbacks2){callbacks=c}
 var receiver:BroadcastReceiver?=null;var callbacks:ComponentCallbacks2?=null}
abstract class BroadcastReceiver{abstract fun onReceive(context:Context,intent:Intent)}
class Intent{val data:Data?=null;companion object{const val ACTION_PACKAGE_ADDED="added";const val ACTION_PACKAGE_REMOVED="removed";const val ACTION_PACKAGE_CHANGED="changed"}}
class Data{val schemeSpecificPart:String?=null}
class IntentFilter{fun addAction(s:String){};fun addDataScheme(s:String){}}
interface ComponentCallbacks2{fun onConfigurationChanged(c:android.content.res.Configuration);fun onLowMemory();fun onTrimMemory(l:Int)
 companion object{const val TRIM_MEMORY_RUNNING_LOW=10}}
''',
'Config.kt':'package android.content.res\nclass Configuration',
'Pm.kt':'''package android.content.pm
class PackageManager{var scans=0;var labels=0;var icons=0;val apps=(1..100).map{ApplicationInfo("app$it")}
 fun getInstalledApplications(flags:Int):List<ApplicationInfo>{scans++;return apps}}
class ApplicationInfo(val packageName:String){fun loadLabel(pm:PackageManager):CharSequence{pm.labels++;if(packageName=="app3")error("missing resource");return "Label $packageName"}
 fun loadIcon(pm:PackageManager):android.graphics.drawable.Drawable{pm.icons++;return android.graphics.drawable.Drawable()}}
''',
'Drawable.kt':'''package android.graphics.drawable
class Drawable{val constantState:ConstantState?=ConstantState();fun mutate()=this
 class ConstantState{fun newDrawable(r:Any)=Drawable()}}
''',
'Lru.kt':'''package android.util
class LruCache<K,V>(val max:Int){val values=linkedMapOf<K,V>();fun get(k:K)=values[k];fun put(k:K,v:V){values[k]=v};fun remove(k:K)=values.remove(k);fun evictAll(){values.clear()}}
''',
'Main.kt':'''package com.zui.zuicontrol
object ResponseTrace{fun mark(event:String,detail:String=""){}}
fun main(args:Array<String>){val pm=android.content.pm.PackageManager();val c=android.content.Context(pm)
 val first=FrontendPackages.read(c);check(first.entries.size==100&&pm.scans==1&&pm.labels==0&&pm.icons==0)
 val subset=FrontendPackages.read(c,setOf("app1","app2"));check(pm.scans==1&&pm.labels==2&&subset.entries.count{it.labelLoaded}==2)
 FrontendPackages.read(c,setOf("app2"));check(pm.labels==2)
 val extended=FrontendPackages.read(c,setOf("app3"));check(pm.labels==3&&extended.entries.count{it.labelLoaded}==3)
 check(extended.entries.single{it.info.packageName=="app3"}.label=="app3")
 FrontendPackages.read(c,pm.apps.map{it.packageName}.toSet());check(pm.labels==100&&pm.scans==1&&pm.icons==0)
 FrontendPackages.read(c,pm.apps.map{it.packageName}.toSet());check(pm.labels==100)
 FrontendPackages.observe(c){};val version=FrontendPackages.version;c.receiver!!.onReceive(c,android.content.Intent())
 check(FrontendPackages.version==version+1);val invalidated=FrontendPackages.read(c,setOf("app1"))
 check(pm.scans==2&&pm.labels==101&&invalidated.entries.count{it.labelLoaded}==1)
 println("METADATA_NAMES_ONCE_LABELS_ON_DEMAND_NO_EAGER_ICONS=PASS")}
'''}
if __name__=='__main__':compile_run(files,'com.zui.zuicontrol.MainKt',[])
