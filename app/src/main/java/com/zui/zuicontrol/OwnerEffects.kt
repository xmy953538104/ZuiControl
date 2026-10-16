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
        updatePaint();updateBounds()
        invalidateSelf()
    }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowBounds=RectF()
    private var shadowRadius=0f
    init{updatePaint()}
    private fun updatePaint(){
        paint.color=tone;paint.setShadowLayer(blur*unit,0f,offset*unit,tone)
    }
    private fun updateBounds(){
        shadowBounds.set(bounds);shadowBounds.inset(-spread*unit,-spread*unit)
        shadowRadius=(radius+spread).coerceAtLeast(0f)*unit
    }
    override fun onBoundsChange(bounds:Rect){surface.bounds=bounds;updateBounds()}
    override fun draw(canvas:Canvas){
        if(shadowBounds.width()>0 && shadowBounds.height()>0){
            canvas.drawRoundRect(shadowBounds,shadowRadius,shadowRadius,paint)
        }
        surface.draw(canvas)
    }
    override fun getOutline(outline:Outline){surface.getOutline(outline)}
    override fun setAlpha(alpha:Int){surface.alpha=alpha}
    override fun setColorFilter(filter:ColorFilter?){surface.colorFilter=filter}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
