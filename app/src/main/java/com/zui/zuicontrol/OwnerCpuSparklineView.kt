package com.zui.zuicontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Future
import android.widget.LinearLayout
import java.util.Locale

internal data class OwnerCpuSample(val time:Double,val cpu:Double)

/** Missing/invalid Top15 samples end the path. Time gaps never interpolate. */
internal object OwnerCpuTimeline {
    fun segments(samples:List<OwnerCpuSample>):List<List<OwnerCpuSample>> {
        val result=mutableListOf<List<OwnerCpuSample>>();var part=mutableListOf<OwnerCpuSample>()
        fun end(){if(part.isNotEmpty())result+=part;part=mutableListOf()}
        for(sample in samples) {
            if(!sample.time.isFinite() || !sample.cpu.isFinite() || sample.cpu<0){end();continue}
            if(part.isNotEmpty() && (sample.time-part.last().time>4000 || sample.time<=part.last().time))end()
            part+=sample
        }
        end();return result
    }
}

/** Owner 88×22 cyan stroke, hardware Canvas; no filled area or invented points. */
internal class OwnerCpuSparklineView(context:Context):View(context) {
    private val owner=OwnerUi(context)
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=owner.zo;style=Paint.Style.STROKE;strokeWidth=1.6f*owner.density
        strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND
    }
    private var segments=emptyList<List<OwnerCpuSample>>()
    private var duration=1L
    init { minimumWidth=owner.px(88);minimumHeight=owner.px(22);contentDescription="CPU 时间线，正在读取" }
    fun bind(rows:JSONArray,duration:Long) {
        this.duration=duration.coerceAtLeast(1)
        segments=OwnerCpuTimeline.segments((0 until rows.length()).map {
            val row=rows.getJSONArray(it);OwnerCpuSample(row.optDouble(0,Double.NaN),row.optDouble(1,Double.NaN))
        })
        contentDescription="CPU 时间线，${segments.sumOf{it.size}} 个有效样本，${segments.size} 段；空白表示无 Top15 样本"
        invalidate()
    }
    fun unavailable(){contentDescription="CPU 时间线暂不可读";invalidate()}
    fun changeTheme(dark:Boolean){owner.changeTheme(dark);ink.color=owner.zo;invalidate()}
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val pad=ink.strokeWidth/2;val high=maxOf(100.0,segments.flatten().maxOfOrNull{it.cpu} ?: 100.0)
        for(segment in segments) {
            val path=Path()
            segment.forEachIndexed{i,p->
                val x=pad+(width-2*pad)*(p.time/duration).coerceIn(0.0,1.0).toFloat()
                val y=height-pad-(height-2*pad)*(p.cpu/high).toFloat()
                if(i==0){path.moveTo(x,y);if(segment.size==1)canvas.drawPoint(x,y,ink)}else path.lineTo(x,y)
            }
            canvas.drawPath(path,ink)
        }
    }
}

/** At most one existing recordRead in flight; cache is scoped to exact record/thread identity. */
internal class OwnerCpuSparklineLoader {
    private val executor=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    private val cache=object:LinkedHashMap<String,JSONArray>(32,.75f,true){
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,JSONArray>?)=size>30
    }
    @Volatile private var epoch=0
    private var pending:Future<*>?=null
    fun cancel(){epoch++;pending?.cancel(true);pending=null}
    fun close(){cancel();executor.shutdownNow()}
    fun show(record:JSONObject,targets:List<Pair<String,OwnerCpuSparklineView>>) {
        cancel();val token=epoch
        val pkg=record.optString("package");val id=record.optLong("recordId");val duration=record.optLong("duration")
        val missing=mutableListOf<Pair<String,OwnerCpuSparklineView>>()
        for((key,view) in targets.take(15)) {
            val cached=cache["$id/$pkg/$key"]
            if(cached!=null)view.bind(cached,duration)else missing+=key to view
        }
        if(missing.isEmpty())return
        pending=executor.submit {
            for((key,view) in missing) {
                if(epoch!=token || Thread.currentThread().isInterrupted)break
                val reply=runCatching{JSONObject(PerformanceMonitor.command("recordRead",
                    JSONObject().put("package",pkg).put("thread",key).put("threads",false).toString()))}
                handler.post {
                    if(epoch!=token)return@post
                    reply.onSuccess{data->
                        if(data.optLong("recordId")==id && data.optString("package")==pkg && data.has("detail")){
                            val rows=data.getJSONArray("detail");cache["$id/$pkg/$key"]=rows;view.bind(rows,duration)
                        }else view.unavailable()
                    }.onFailure{view.unavailable()}
                }
            }
        }
    }
}

/** The same Owner table is used by the main page and the retained internal Activity. */
internal fun OwnerUi.cpuThreadTable(record:JSONObject,loader:OwnerCpuSparklineLoader,open:((String,String)->Unit)?=null):View=card().apply {
    setPadding(px(14),px(8),px(14),px(8))
    val rows=record.optJSONArray("threads") ?: JSONArray()
    val count=minOf(15,rows.length())
    val same=(0 until count).groupingBy{rows.getJSONArray(it).optString(1)}.eachCount()
    val widths=listOf(0,56,80,70,70,88)
    addView(tableRow(listOf("线程名称","TID","入榜均值","峰值","入榜样本","CPU 时间线").map{label(it,11.5f,muted,700)},widths,true))
    val targets=mutableListOf<Pair<String,OwnerCpuSparklineView>>()
    fun number(value:Double)=if(!value.isFinite() || value<0)"--"else String.format(Locale.ROOT,"%.1f",value)
    for(i in 0 until count) {
        val row=rows.getJSONArray(i);val key=row.optString(0);val name=row.optString(1)
        val texts=listOf(name+(if((same[name] ?: 0)>1)" · 同名 ${same[name]}"else ""),
            key.split(':').getOrNull(2).orEmpty(),"${number(row.optDouble(2))}%","${number(row.optDouble(3))}%",row.optLong(4).toString())
        val spark=OwnerCpuSparklineView(context).apply{tag=key}
        targets+=key to spark
        addView(tableRow(texts.map{label(it,13f,text,700)}+spark,widths).apply{if(open!=null){isFocusable=true;contentDescription="查看 $name CPU 时间线";setOnClickListener{open(key,name)}}})
    }
    if(count==0)addView(empty("暂无线程样本","断档表示未保存 Top15 样本，不代表 CPU=0。"))
    loader.show(record,targets)
}
