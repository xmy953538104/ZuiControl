package com.zui.zuicontrol

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import org.json.JSONArray
import java.util.Locale

    internal class RecordChart(context: android.content.Context, private val rows: JSONArray, private val column: Int, private val duration: Long) : View(context) {
        private val density get() = resources.displayMetrics.density
    private fun dp(n: Int) = (n * density).toInt()
    private fun getColor(id: Int) = context.getColor(id)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val values = (0 until rows.length()).map { rows.getJSONArray(it).optDouble(column, Double.NaN) }
        private val axis = RecordAxis.of(values)
        private val owner=OwnerUi(context)
        fun changeTheme(dark:Boolean){owner.changeTheme(dark);invalidate()}
        init { contentDescription = "时间曲线；${values.count { it.isFinite() && it >= 0 }} 个有效汇总点" }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val left = dp(32).toFloat(); val right = width - dp(8).toFloat()
            val top = dp(8).toFloat(); val bottom = height - dp(24).toFloat()
            if (right <= left || bottom <= top) return
            paint.textSize = 10f * density; paint.typeface=android.graphics.Typeface.create(android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL),600,false);paint.strokeWidth = density
            axis.ticks.forEach { value ->
                val y = bottom - (bottom - top) * ((value - axis.low) / (axis.high - axis.low)).toFloat()
                paint.color = owner.line;paint.pathEffect=android.graphics.DashPathEffect(floatArrayOf(3*density,4*density),0f);canvas.drawLine(left, y, right, y, paint);paint.pathEffect=null
                paint.color = owner.muted; paint.textAlign = Paint.Align.RIGHT
                canvas.drawText(axis.label(value), left - dp(8), y - (paint.ascent() + paint.descent()) / 2, paint)
            }
            for (i in 0..2) {
                val seconds = duration * i / 2000
                val text = if (duration < 120000) "${seconds}s" else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
                paint.textAlign = when (i) { 0 -> Paint.Align.LEFT; 2 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                canvas.drawText(text, left + (right - left) * i / 2, bottom + dp(22), paint)
            }
            if (values.none { it.isFinite() && it >= 0 }) {
                paint.textAlign = Paint.Align.CENTER; canvas.drawText("暂无有效值", (left + right) / 2, (top + bottom) / 2, paint); return
            }
            var path = Path(); var connected = false; var previous = -1.0
            var firstX=0f;var lastX=0f;var lastY=0f
            val gapLimit = maxOf(4000.0, duration / 600.0 * 2.5)
            val tone=when(column){2->owner.tiers[0];3->owner.tiers[2];else->owner.accent}
            fun finishSegment(){
                if(!connected)return
                val area=Path(path).apply{lineTo(lastX,bottom);lineTo(firstX,bottom);close()}
                paint.style=Paint.Style.FILL
                paint.shader=android.graphics.LinearGradient(0f,top,0f,bottom,owner.soft(tone,71),owner.soft(tone,0),android.graphics.Shader.TileMode.CLAMP)
                canvas.drawPath(area,paint);paint.shader=null
                paint.color=tone;paint.style=Paint.Style.STROKE;paint.strokeWidth=2*density;paint.strokeJoin=Paint.Join.ROUND;paint.strokeCap=Paint.Cap.ROUND
                paint.setShadowLayer(8*density,0f,0f,owner.soft(tone,128))
                canvas.drawPath(path,paint);paint.clearShadowLayer();paint.style=Paint.Style.FILL
                paint.shader=android.graphics.RadialGradient(lastX,lastY,8*density,intArrayOf(owner.soft(tone,128),owner.soft(tone,0)),null,android.graphics.Shader.TileMode.CLAMP)
                canvas.drawCircle(lastX,lastY,8*density,paint);paint.shader=null
                paint.color=owner.card;canvas.drawCircle(lastX,lastY,3*density,paint)
                paint.color=tone;paint.style=Paint.Style.STROKE;paint.strokeWidth=2*density;canvas.drawCircle(lastX,lastY,3*density,paint);paint.style=Paint.Style.FILL
                path=Path();connected=false
            }
            for (i in 0 until rows.length()) {
                val t = rows.getJSONArray(i).optDouble(0); val value = values[i]
                if (!value.isFinite() || value < 0) { finishSegment(); continue }
                val x = left + (right - left) * (t / duration.coerceAtLeast(1)).toFloat()
                val y = bottom - (bottom - top) * ((value - axis.low) / (axis.high - axis.low)).toFloat()
                if(connected && t-previous>gapLimit)finishSegment()
                if (!connected) {path.moveTo(x, y);firstX=x} else path.lineTo(x, y)
                lastX=x;lastY=y
                connected = true; previous = t
            }
            finishSegment()
        }
    }
