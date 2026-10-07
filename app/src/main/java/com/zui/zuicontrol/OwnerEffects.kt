package com.zui.zuicontrol

import android.graphics.*
import android.graphics.drawable.Drawable

/** Owner CSS box-shadow geometry, rendered by hardware Canvas (no CPU blur loop). */
internal class OwnerShadowDrawable(private val surface: Drawable, private val unit: Float,
    private val radius: Float, private var blur: Float, private var offset: Float,
    private var spread: Float, private var tone: Int): Drawable() {
    fun retheme(dark:Boolean,color:(Int)->Int,child:(Drawable?)->Unit){
        child(surface)
        if(tone==0x8c000000.toInt() || tone==0x0d0f172a){tone=if(dark)0x8c000000.toInt() else 0x0d0f172a;blur=if(dark)30f else 10f;offset=if(dark)10f else 2f;spread=if(dark)-12f else 0f}
        else tone=color(tone)
        shadowNode=null
        invalidateSelf()
    }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var shadowNode:RenderNode?=null
    override fun onBoundsChange(bounds:Rect){surface.bounds=bounds;shadowNode=null}
    private fun drawShadow(canvas:Canvas){
        val rect=RectF(bounds).apply{inset(-spread*unit,-spread*unit)}
        if(rect.width()>0 && rect.height()>0){
            paint.color=tone;paint.setShadowLayer(blur*unit,0f,offset*unit,tone)
            canvas.drawRoundRect(rect,(radius+spread).coerceAtLeast(0f)*unit,(radius+spread).coerceAtLeast(0f)*unit,paint)
            paint.clearShadowLayer()
        }
    }
    override fun draw(canvas:Canvas){
        if(canvas.isHardwareAccelerated && !bounds.isEmpty){
            val node=shadowNode?.takeIf{it.hasDisplayList()} ?: run {
                // Cache the same hardware shadow commands, including their overflow.
                // Animated siblings can then composite this layer without repeating blur.
                val edge=kotlin.math.ceil((blur*2+ kotlin.math.abs(offset)+ kotlin.math.abs(spread))*unit).toInt()+2
                val area=Rect(bounds).apply{inset(-edge,-edge)}
                RenderNode("Owner shadow").apply{
                    setPosition(area.left,area.top,area.right,area.bottom)
                    setClipToBounds(false);setUseCompositingLayer(true,null)
                    val recording=beginRecording(area.width(),area.height())
                    recording.translate(-area.left.toFloat(),-area.top.toFloat());drawShadow(recording);endRecording()
                }.also{shadowNode=it}
            }
            canvas.drawRenderNode(node)
        }else drawShadow(canvas)
        surface.draw(canvas)
    }
    override fun getOutline(outline:Outline){surface.getOutline(outline)}
    override fun setAlpha(alpha:Int){surface.alpha=alpha}
    override fun setColorFilter(filter:ColorFilter?){surface.colorFilter=filter}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
