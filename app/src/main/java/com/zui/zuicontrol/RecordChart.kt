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
        init { contentDescription = "时间曲线；${values.count { it.isFinite() && it >= 0 }} 个有效汇总点" }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val left = dp(48).toFloat(); val right = width - dp(16).toFloat()
            val top = dp(20).toFloat(); val bottom = height - dp(32).toFloat()
            if (right <= left || bottom <= top) return
            paint.textSize = 11 * resources.displayMetrics.scaledDensity; paint.strokeWidth = density
            axis.ticks.forEach { value ->
                val y = bottom - (bottom - top) * ((value - axis.low) / (axis.high - axis.low)).toFloat()
                paint.color = owner.line;paint.pathEffect=android.graphics.DashPathEffect(floatArrayOf(4*density,4*density),0f);canvas.drawLine(left, y, right, y, paint);paint.pathEffect=null
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
            val path = Path(); var connected = false; var previous = -1.0
            val gapLimit = maxOf(4000.0, duration / 600.0 * 2.5)
            paint.color = when(column){2->owner.tiers[0];3->owner.tiers[2];else->owner.accent}
            for (i in 0 until rows.length()) {
                val t = rows.getJSONArray(i).optDouble(0); val value = values[i]
                if (!value.isFinite() || value < 0) { connected = false; continue }
                val x = left + (right - left) * (t / duration.coerceAtLeast(1)).toFloat()
                val y = bottom - (bottom - top) * ((value - axis.low) / (axis.high - axis.low)).toFloat()
                if (!connected || t - previous > gapLimit) path.moveTo(x, y) else path.lineTo(x, y)
                canvas.drawCircle(x, y, density, paint)
                connected = true; previous = t
            }
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.8f * density
            canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
        }
    }
