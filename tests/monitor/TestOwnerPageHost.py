"""Execute the real page host with Android's shared/detached observer boundary model."""
from pathlib import Path
import sys
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tests/command_plane'))
from TestUtilityTruth import compile_run

def check_page_host():
    files={
    'Context.kt':'package android.content\nclass Context',
    'View.kt':'''package android.view
import android.content.Context
class MotionEvent
class ViewTreeObserver{
 interface OnPreDrawListener{fun onPreDraw():Boolean}
 val callbacks=linkedSetOf<OnPreDrawListener>();var isAlive=true
 fun addOnPreDrawListener(l:OnPreDrawListener){callbacks.add(l)}
 fun removeOnPreDrawListener(l:OnPreDrawListener){callbacks.remove(l)}
 fun fire(){callbacks.toList().forEach{it.onPreDraw()}}
}
open class View(val context:Context){
 val floatingObserver=ViewTreeObserver();var attachedObserver:ViewTreeObserver?=null
 val viewTreeObserver get()=attachedObserver ?: floatingObserver
 var translationY=0f;val isLaidOut=true
 open fun onDetachedFromWindow(){}
}
''',
    'Frame.kt':'''package android.widget
import android.content.Context
import android.view.*
open class FrameLayout(c:Context):View(c){
 class LayoutParams(val width:Int,val height:Int)
 val children=mutableListOf<View>();val childCount get()=children.size
 fun getChildAt(i:Int)=children[i]
 fun addView(v:View,p:LayoutParams){children.add(v);v.attachedObserver=viewTreeObserver}
 fun removeView(v:View){children.remove(v);v.attachedObserver=null}
 fun removeViewAt(i:Int){removeView(children[i])}
 fun removeAllViews(){children.toList().forEach{removeView(it)}}
 open fun onInterceptTouchEvent(e:MotionEvent)=false
 open fun onTouchEvent(e:MotionEvent)=false
}
''',
    'Animator.kt':'''package android.animation
open class Animator
open class AnimatorListenerAdapter{open fun onAnimationEnd(animation:Animator){}}
class ValueAnimator:Animator(){
 var duration=0L;var interpolator:Any?=null;var animatedValue:Any=0f
 private val ends=mutableListOf<AnimatorListenerAdapter>();private val updates=mutableListOf<(ValueAnimator)->Unit>()
 fun addUpdateListener(l:(ValueAnimator)->Unit){updates.add(l)}
 fun addListener(l:AnimatorListenerAdapter){ends.add(l)}
 fun start(){running.add(this)}
 fun cancel(){if(running.remove(this))ends.forEach{it.onAnimationEnd(this)}}
 fun finish(){updates.forEach{it(this)};ends.forEach{it.onAnimationEnd(this)}}
 companion object{var enabled=false;val running=mutableListOf<ValueAnimator>();fun areAnimatorsEnabled()=enabled
 fun ofFloat(a:Float,b:Float)=ValueAnimator().apply{animatedValue=b}
 fun finishAll(){running.toList().also{running.clear()}.forEach{it.finish()}}}
}
''',
    'Interpolator.kt':'package android.view.animation\nclass PathInterpolator(a:Float,b:Float,c:Float,d:Float)',
    'OwnerPageHost.kt':(ROOT/'app/src/main/java/com/zui/zuicontrol/OwnerPageHost.kt').read_text(encoding='utf-8'),
    'Harness.kt':'''package com.zui.zuicontrol
import android.content.Context
import android.view.*
import android.animation.ValueAnimator
typealias LayoutParams=android.widget.FrameLayout.LayoutParams
object OwnerRenderTrace{fun event(k:String,p:String="") {}}
fun main(){
 val c=Context();val host=OwnerPageHost(c);val observer=host.viewTreeObserver
 repeat(30){host.show(View(c),false,10);check(host.childCount==1)}
 check(observer.callbacks.size==1){"Cancelled detached pages retained ${observer.callbacks.size} shared-root callbacks"}
 observer.fire();check(observer.callbacks.isEmpty()){ "Pre-draw failed to unregister itself from its original observer" }
 ValueAnimator.enabled=true
 repeat(30){host.show(View(c),true,10);check(host.childCount<=2);check(observer.callbacks.size==1)}
 observer.fire();ValueAnimator.finishAll();check(host.childCount==1);check(host.getChildAt(0).translationY==0f);check(observer.callbacks.isEmpty())
 host.show(View(c),true,10);host.release();check(observer.callbacks.isEmpty());check(host.childCount==0)
 println("EXACT_PAGE_HOST_CANCEL_DETACH_RELEASE=PASS")
}
'''}
    compile_run(files,'com.zui.zuicontrol.HarnessKt',[])

if __name__=='__main__':check_page_host()
