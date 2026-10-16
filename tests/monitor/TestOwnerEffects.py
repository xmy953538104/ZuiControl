"""Run the production shadow Drawable: stable filter identity and unchanged geometry."""
from pathlib import Path
import sys
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tests/command_plane'))
from TestUtilityTruth import compile_run

files={
 'Graphics.kt':'''package android.graphics
class Rect(var left:Int=0,var top:Int=0,var right:Int=0,var bottom:Int=0)
class RectF(r:Rect=Rect()){
 var left=r.left.toFloat();var top=r.top.toFloat();var right=r.right.toFloat();var bottom=r.bottom.toFloat()
 fun set(r:Rect){left=r.left.toFloat();top=r.top.toFloat();right=r.right.toFloat();bottom=r.bottom.toFloat()}
 fun inset(x:Float,y:Float){left+=x;right-=x;top+=y;bottom-=y}
 fun width()=right-left;fun height()=bottom-top
}
class Paint(flags:Int){var color=0;var sets=0;var clears=0;var shadow=listOf<Float>();var shadowColor=0
 fun setShadowLayer(r:Float,x:Float,y:Float,c:Int){sets++;shadow=listOf(r,x,y);shadowColor=c}
 fun clearShadowLayer(){clears++}
 companion object{const val ANTI_ALIAS_FLAG=1}}
class Canvas{var paint:Paint?=null;var geometry=listOf<Float>();var draws=0
 fun drawRoundRect(r:RectF,x:Float,y:Float,p:Paint){paint=p;geometry=listOf(r.left,r.top,r.right,r.bottom,x,y);draws++}}
class Outline
class ColorFilter
object PixelFormat{const val TRANSLUCENT=-3}
''',
 'Drawable.kt':'''package android.graphics.drawable
import android.graphics.*
abstract class Drawable{
 @JvmField var alpha=255
 @JvmField var colorFilter:ColorFilter?=null
 var bounds=Rect();set(value){field=value;onBoundsChange(value)}
 open fun onBoundsChange(r:Rect){}
 open fun invalidateSelf(){}
 abstract fun draw(c:Canvas)
 open fun getOutline(o:Outline){}
 open fun setAlpha(a:Int){}
 open fun setColorFilter(f:ColorFilter?){}
 open fun getOpacity()=0
}
''',
 'OwnerEffects.kt':(ROOT/'app/src/main/java/com/zui/zuicontrol/OwnerEffects.kt').read_text(encoding='utf8'),
 'Harness.kt':'''package com.zui.zuicontrol
import android.graphics.*
import android.graphics.drawable.Drawable
class Surface:Drawable(){var draws=0;override fun draw(c:Canvas){draws++}}
fun main(){
 val surface=Surface();val drawable=OwnerShadowDrawable(surface,2f,18f,30f,10f,-12f,0x8c000000.toInt());val c=Canvas()
 drawable.bounds=Rect(10,20,210,120);repeat(100){drawable.draw(c)}
 val p=checkNotNull(c.paint);check(p.sets==1&&p.clears==0){"Shadow filter recreated ${p.sets} times, cleared ${p.clears} times"}
 check(c.geometry==listOf(34f,44f,186f,96f,12f,12f));check(p.shadow==listOf(60f,0f,20f));check(surface.draws==100)
 drawable.retheme(false,{it},{check(it===surface)});drawable.draw(c)
 check(c.geometry==listOf(10f,20f,210f,120f,36f,36f));check(p.shadow==listOf(20f,0f,4f));check(p.shadowColor==0x0d0f172a)
 val count=p.sets;repeat(100){drawable.draw(c)};check(p.sets==count&&p.clears==0)
 drawable.retheme(true,{it},{ });drawable.bounds=Rect(0,0,10,10);val draws=c.draws;drawable.draw(c);check(c.draws==draws){"Empty spread geometry must stay invisible"};check(surface.draws==202)
 drawable.bounds=Rect(5,6,205,106);drawable.draw(c);check(c.geometry==listOf(29f,30f,181f,82f,12f,12f));check(p.shadow==listOf(60f,0f,20f))
 println("PRODUCTION_SHADOW_FILTER_REUSE_AND_GEOMETRY=PASS")
}
'''}
if __name__=='__main__':compile_run(files,'com.zui.zuicontrol.HarnessKt',[])
