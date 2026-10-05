package com.zui.zuicontrol

import android.graphics.*
import android.graphics.drawable.Drawable

/** Owner CSS box-shadow geometry, rendered by hardware Canvas (no CPU blur loop). */
internal class OwnerShadowDrawable(private val surface: Drawable, private val unit: Float,
    private val radius: Float, private val blur: Float, private val offset: Float,
    private val spread: Float, private val tone: Int): Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onBoundsChange(bounds:Rect){surface.bounds=bounds}
    override fun draw(canvas:Canvas){
        val rect=RectF(bounds).apply{inset(-spread*unit,-spread*unit)}
        if(rect.width()>0 && rect.height()>0){
            paint.color=tone;paint.setShadowLayer(blur*unit,0f,offset*unit,tone)
            canvas.drawRoundRect(rect,(radius+spread).coerceAtLeast(0f)*unit,(radius+spread).coerceAtLeast(0f)*unit,paint)
            paint.clearShadowLayer()
        }
        surface.draw(canvas)
    }
    override fun getOutline(outline:Outline){surface.getOutline(outline)}
    override fun setAlpha(alpha:Int){surface.alpha=alpha}
    override fun setColorFilter(filter:ColorFilter?){surface.colorFilter=filter}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
