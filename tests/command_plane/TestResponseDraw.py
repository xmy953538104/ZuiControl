"""One draw must validate the current page/authority, then retire its listener."""
from pathlib import Path
from TestUtilityTruth import compile_run
ROOT=Path(__file__).resolve().parents[2]
import sys
sys.path.insert(0,str(ROOT/'tests'))
from FrontendSource import read_frontend
app=ROOT/'app/src/main/java/com/zui/zuicontrol'
source=read_frontend(app/'MainActivity.kt')
assert 'responseFrameKey==responseKey' in source
assert '(!editable || rulesQualified && inventoryVersion==FrontendPackages.version)' in source
assert 'session.notice==notice && session.busy==busy' in source
files={
'ResponseTrace.kt':(app/'ResponseTrace.kt').read_text('utf8'),
'Os.kt':'''package android.os
object Process{fun myPid()=1}
object SystemClock{fun elapsedRealtimeNanos()=1L}
''',
'Log.kt':'''package android.util
object Log{const val VERBOSE=2;var enabled=true;val rows=mutableListOf<String>();fun isLoggable(t:String,l:Int)=enabled;fun i(t:String,s:String):Int{rows+=s;return 0}}
''',
'Json.kt':'''package org.json
class JSONObject{val values=linkedMapOf<String,Any>();fun put(k:String,v:Any):JSONObject{values[k]=v;return this};override fun toString()=values.toString()}
''',
'View.kt':'''package android.view
class ViewTreeObserver{interface OnDrawListener{fun onDraw()};val listeners=mutableListOf<OnDrawListener>();var isAlive=true
 fun addOnDrawListener(l:OnDrawListener){listeners+=l};fun removeOnDrawListener(l:OnDrawListener){listeners-=l}}
class View{val viewTreeObserver=ViewTreeObserver();val posted=mutableListOf<()->Unit>();fun post(f:()->Unit){posted+=f}
 fun draw(){viewTreeObserver.listeners.toList().forEach{it.onDraw()};posted.toList().forEach{it()};posted.clear()}}
''',
'Main.kt':'''package com.zui.zuicontrol
fun main(args:Array<String>){val view=android.view.View();var current=true
 ResponseTrace.drawn(view,"old-page","EDITABLE_DRAW","thread"){current};current=false;view.draw()
 check(android.util.Log.rows.isEmpty()&&view.viewTreeObserver.listeners.isEmpty())
 current=true;ResponseTrace.drawn(view,"current-page","EDITABLE_DRAW","thread"){current};view.draw();view.draw()
 check(android.util.Log.rows.size==1&&android.util.Log.rows.single().contains("current-page")&&android.util.Log.rows.single().contains("detail=thread"))
 android.util.Log.enabled=false;ResponseTrace.drawn(view,"disabled","CONTENT_DRAW");check(view.viewTreeObserver.listeners.isEmpty());view.draw();check(android.util.Log.rows.size==1)
 println("DRAW_TIME_AUTHORITY_PAGE_GUARD_AND_ONE_SHOT_RETIREMENT=PASS")}
'''}
if __name__=='__main__':compile_run(files,'com.zui.zuicontrol.MainKt',[])
