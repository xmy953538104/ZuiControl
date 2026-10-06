package com.zui.zuicontrol

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Eight persistent CPU cells; a mask update never reconstructs the picker. */
internal class OwnerCpuPicker(context:Context,private val ui:OwnerUi,initial:Set<Int>,private val large:Boolean,
    action:(Set<Int>)->Boolean):LinearLayout(context) {
    var selected=initial;private set
    init {
        orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;tag="owner-control"
        for(cpu in 0..7)addView(ui.label(if(large)cpu.toString()else "",10f,ui.muted,700).apply{
            gravity=Gravity.CENTER;isFocusable=true
            setOnClickListener{val next=if(cpu in selected)selected-cpu else selected+cpu;if(action(next))showSelection(next)};ui.press(this)
        },LayoutParams(ui.px(if(large)26 else 12),ui.px(if(large)26 else 16)).apply{if(cpu>0)marginStart=ui.px(if(large)5 else 3)})
        showSelection(initial)
    }
    fun showSelection(mask:Set<Int>){selected=mask;for(cpu in 0..7){
        val on=cpu in selected;val cell=getChildAt(cpu) as TextView
        cell.setTextColor(if(on)Color.parseColor("#04131A")else ui.muted)
        cell.background=ui.shape(if(on)ui.zo else ui.card2,if(large)7f else 3f,if(on)null else ui.line2)
        cell.contentDescription="CPU $cpu，${if(on)"已选择" else "未选择"}"
    }}
}
