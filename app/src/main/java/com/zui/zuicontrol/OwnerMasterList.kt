package com.zui.zuicontrol

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** One viewport of rows. Policy, selection and drafts remain owned by the Activity/session. */
internal class OwnerMasterList(context:Context,private val ui:OwnerUi,
    private val icon:(String)->Drawable?,private val choose:(String)->Unit):FrameLayout(context) {
    data class Chip(val text:String,val tier:Int=0,val created:Boolean=false)
    data class Row(val key:String,val title:String,val subtitle:String="",val chips:List<Chip> = emptyList(),val ruleMarker:Boolean=false)
    private var rows=emptyList<Row>()
    private var selected=""
    private var section=""
    private var ticket=0L
    private var restoreScroll:Runnable?=null
    private var createdRowCount=0
    private var bindCount=0
    private val empty=ui.label("暂无应用",11.5f,ui.sub).apply{setPadding(0,ui.px(4),0,ui.px(4))}
    private val list=ListView(context).apply{isVerticalScrollBarEnabled=false;divider=null;dividerHeight=0;selector=ColorDrawable(Color.TRANSPARENT);cacheColorHint=Color.TRANSPARENT;clipChildren=false;clipToPadding=false}
    private val adapter=object:BaseAdapter(){
        override fun getCount()=rows.size
        override fun getItem(position:Int)=rows[position]
        override fun getItemId(position:Int)=position.toLong()
        override fun getView(position:Int,recycled:View?,parent:ViewGroup):View {
            val row=recycled as? RowView ?: RowView().also{createdRowCount++}
            bindCount++;row.bind(rows[position],rows[position].key==selected);return row
        }
    }
    init{
        clipChildren=false;setPadding(ui.px(14),ui.px(4),ui.px(14),ui.px(18))
        addView(list,LayoutParams(-1,-1));addView(empty,LayoutParams(-1,-2));list.emptyView=empty;list.adapter=adapter
    }
    fun showSection(value:String){
        if(section==value)return
        section=value;ticket++;restoreScroll?.let(list::removeCallbacks);restoreScroll=null
        rows=emptyList();selected="";adapter.notifyDataSetChanged();list.setSelection(0)
    }
    fun submit(next:List<Row>,message:String) {
        empty.text=message
        if(rows==next)return
        val anchor=rows.getOrNull(list.firstVisiblePosition)?.key
        val offset=list.getChildAt(0)?.top ?: 0
        restoreScroll?.let(list::removeCallbacks)
        rows=next.toList();val current=++ticket;adapter.notifyDataSetChanged()
        val position=rows.indexOfFirst{it.key==anchor}
        restoreScroll=Runnable{if(ticket==current){list.setSelectionFromTop(position.coerceAtLeast(0),if(position<0)0 else offset);restoreScroll=null}}.also(list::post)
    }
    fun select(key:String){
        if(selected==key)return
        selected=key
        for(i in 0 until list.childCount){val position=list.firstVisiblePosition+i;rows.getOrNull(position)?.let{(list.getChildAt(i) as? RowView)?.bind(it,it.key==selected)}}
    }
    override fun onDetachedFromWindow(){ticket++;restoreScroll?.let(list::removeCallbacks);restoreScroll=null;super.onDetachedFromWindow()}
    private inner class RowView:FrameLayout(context) {
        private var key=""
        private var bound:Row?=null
        private var palette:Boolean?=null
        private var selection:Boolean?=null
        private val card=ui.row().apply{setPadding(ui.px(13),ui.px(12),ui.px(13),ui.px(12))}
        private val image=ImageView(context).apply{scaleType=ImageView.ScaleType.FIT_CENTER;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
        private val title=ui.label("",13.5f,ui.text,800)
        private val chips=ui.row()
        private val chevron=ui.icon(R.drawable.owner_chevron,ui.muted,16).apply{alpha=.6f}
        init{
            clipChildren=false;setPadding(0,0,0,ui.px(8));addView(card,LayoutParams(-1,-2))
            card.addView(image,LinearLayout.LayoutParams(ui.px(40),ui.px(40)).apply{marginEnd=ui.px(12)})
            card.addView(ui.column().apply{addView(title,LinearLayout.LayoutParams(-1,ui.px(17)));addView(chips,LinearLayout.LayoutParams(-1,-2).apply{topMargin=ui.px(5)})},LinearLayout.LayoutParams(0,-2,1f))
            card.addView(chevron,LinearLayout.LayoutParams(ui.px(16),ui.px(16)).apply{marginStart=ui.px(12)})
            isFocusable=true;setOnClickListener{choose(key)}
        }
        fun bind(value:Row,on:Boolean){
            key=value.key
            val changed=bound!=value||palette!=ui.dark
            if(changed){
                image.setImageDrawable(icon(value.key));title.text=value.title;title.setTextColor(ui.text);chevron.imageTintList=android.content.res.ColorStateList.valueOf(ui.muted);chips.removeAllViews()
                if(value.chips.isEmpty())chips.addView(ui.label(value.subtitle,11f,ui.muted),LinearLayout.LayoutParams(-1,ui.px(14)))
                else value.chips.forEachIndexed{i,c->chips.addView(ui.chip(c.text,c.tier).apply{if(c.created){setTextColor(ui.zoFg);background=ui.shape(ui.zoBg,5f)}},LinearLayout.LayoutParams(-2,ui.px(18)).apply{if(i>0)marginStart=ui.px(6)})}
                if(value.ruleMarker)chips.addView(ui.icon(R.drawable.owner_chip,ui.zoFg,12).apply{background=ui.shape(ui.zoBg,5f);setPadding(ui.px(3),ui.px(3),ui.px(3),ui.px(3))},LinearLayout.LayoutParams(ui.px(18),ui.px(18)).apply{marginStart=ui.px(6)})
                contentDescription=value.title;bound=value
            }
            if(changed||selection!=on){
                isSelected=on;card.background=ui.shape(ui.card,14f,if(on)ui.accent else ui.line,if(on)2f else 1f)
                if(on)card.background=ui.shadow(checkNotNull(card.background),14f,24f,10f,-12f,ui.accentGlow)
                selection=on;palette=ui.dark
            }
        }
    }
}
