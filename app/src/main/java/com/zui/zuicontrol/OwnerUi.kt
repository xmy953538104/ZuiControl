package com.zui.zuicontrol

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Build
import android.util.TypedValue
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*
import kotlin.math.*

/** Native port of FRONTEND_FINAL_V83.html. Dimensions are Owner CSS pixels. */
internal data class OwnerIcon(val resource:Int,val size:Int)
/** White control ink (including translucent units) is invariant across palettes. */
internal fun ownerForegroundColor(value:Int,recolor:(Int)->Int)=if(value and 0xffffff==0xffffff)value else recolor(value)
internal class OwnerUi(val context: Context) {
    var dark = OwnerWindow.dark(context);private set
    private fun c(d: String, l: String):Int=Color.parseColor(if(dark)d else l)
    val rail get()=c("#0D1320","#FFFFFF"); val master get()=c("#0D1320","#F6F8FB"); val detail get()=c("#090D15","#EEF2F7")
    val card get()=c("#111927","#FFFFFF"); val card2 get()=c("#1A2334","#F1F4F9")
    val text get()=c("#E8EDF6","#0F172A"); val sub get()=c("#A5B0C3","#475569"); val muted get()=c("#6C7890","#8E9BAE")
    val code get()=c("#0A101A","#F8FAFC")
    val accentSoft get()=c("#245B8CFF","#173562C6"); val accentGlow get()=c("#665B8CFF","#4D3562C6")
    val toastBg get()=c("#F0E8EDF6","#EB0F172A"); val toastFg get()=c("#0B1120","#FFFFFF")
    val accent get()=c("#5B8CFF","#3562C6"); val line get()=c("#1A94A3B8","#E3E8EF"); val line2 get()=c("#3894A3B8","#CBD5E1")
    val zo get()=c("#22C3E6","#0891B2"); val zoBg get()=c("#0D3340","#CFFAFE"); val zoFg get()=c("#5FD9F3","#155E75")
    val tiers get()=intArrayOf(c("#1ECB8E","#10B981"),accent,c("#FF8A3D","#EA580C"),c("#FF4D5E","#DC2626"))
    val inks get()=intArrayOf(c("#3DDC9F","#047857"),c("#7FA5FF","#2B50A3"),c("#FFA163","#C2410C"),c("#FF7482","#B91C1C"))
    val chipBg get()=intArrayOf(c("#1C2536","#F1F5F9"),c("#123A2E","#DCFCE7"),c("#1A2C52","#DBEAFE"),c("#3B2716","#FFEDD5"),c("#3E1B23","#FEE2E2"))
    val chipFg get()=intArrayOf(sub,c("#5EE0A8","#166534"),c("#8FB0FF","#1E40AF"),c("#FFAA70","#9A3412"),c("#FF8A96","#991B1B"))
    fun changeTheme(value:Boolean){dark=value}
    private val borders=java.util.WeakHashMap<GradientDrawable,Pair<Int?,Float>>()
    // Toast foreground has its own role: its light white aliases the card surface.
    private fun palette()=listOf(card,card2,text,sub,muted,code,accentSoft,accentGlow,toastBg,accent,line,line2,zo,zoBg,zoFg)+tiers.toList()+inks.toList()+chipBg.toList()+chipFg.toList()
    /** Recolor attached views; their identities, font metrics and layout remain untouched. */
    fun applyTheme(root:View,value:Boolean){
        val old=palette();val wasDark=dark;val oldSoft=tiers.map{soft(it)}
        dark=value;val next=palette();val mapping=old.zip(next).toMap()+oldSoft.zip(tiers.map{soft(it)}).toMap()
        fun color(v:Int):Int=mapping[v] ?: old.zip(next).firstOrNull{(a,_)->(a and 0xffffff)==(v and 0xffffff) && Color.alpha(a)==255}?.let{(_,b)->(v and -0x1000000) or (b and 0xffffff)} ?: v
        fun drawable(d:android.graphics.drawable.Drawable?) {
            when(d){
                is OwnerShadowDrawable->d.retheme(value,::color,::drawable)
                is GradientDrawable->{d.color?.defaultColor?.let{d.setColor(color(it))};borders[d]?.let{(border,width)->if(border!=null){val fresh=color(border);d.setStroke(px(width).coerceAtLeast(1),fresh);borders[d]=fresh to width}}}
                is android.graphics.drawable.ColorDrawable->d.color=color(d.color)
            }
        }
        fun visit(v:View){
            drawable(v.background)
            if(v is TextView){v.setTextColor(if(v.tag=="owner-toast-foreground")toastFg else ownerForegroundColor(v.currentTextColor,::color));if(v is EditText)v.setHintTextColor(color(v.currentHintTextColor))
                (v.text as? android.text.Spannable)?.let{s->s.getSpans(0,s.length,android.text.style.ForegroundColorSpan::class.java).forEach{span->val a=s.getSpanStart(span);val b=s.getSpanEnd(span);val flags=s.getSpanFlags(span);s.removeSpan(span);s.setSpan(android.text.style.ForegroundColorSpan(ownerForegroundColor(span.foregroundColor,::color)),a,b,flags)}}
            }
            if(v is ImageView)v.imageTintList?.defaultColor?.let{v.imageTintList=android.content.res.ColorStateList.valueOf(ownerForegroundColor(it,::color))}
            when(v){is OwnerCpuSparklineView->v.changeTheme(value);is GpuRangeBar->v.changeTheme(value);is RecordChart->v.changeTheme(value);is OwnerPing->v.retheme(::color);is OwnerMeter->v.retheme(::color);is OwnerCpuPicker->v.showSelection(v.selected)}
            if(v is ViewGroup)for(i in 0 until v.childCount)visit(v.getChildAt(i))
            v.invalidate()
        }
        if(wasDark!=value || old!=next)visit(root)
    }
    val density=context.resources.displayMetrics.density
    fun px(v: Number)=(v.toFloat()*density).roundToInt()
    fun soft(color: Int, alpha: Int = if(dark)36 else if(color==tiers[0] || color==tiers[2])26 else 23)=(color and 0xffffff) or (alpha shl 24)
    fun shape(color: Int, radius: Float, border: Int? = null, stroke: Float=1f) = GradientDrawable().apply {
        setColor(color); cornerRadius=px(radius).toFloat(); if(border!=null)setStroke(px(stroke).coerceAtLeast(1),border)
        borders[this]=border to stroke
    }
    fun shadow(surface:android.graphics.drawable.Drawable,radius:Float,blur:Float,offset:Float,spread:Float,tone:Int)=OwnerShadowDrawable(surface,density,radius,blur,offset,spread,tone)
    fun row()=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;clipChildren=false;clipToPadding=false }
    fun column()=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;clipChildren=false;clipToPadding=false }
    fun borderedColumn(content: View,width: Int)=FrameLayout(context).apply {
        addView(content,FrameLayout.LayoutParams(-1,-1).apply{rightMargin=px(1)})
        addView(View(context).apply{setBackgroundColor(line)},FrameLayout.LayoutParams(px(1),-1,Gravity.END))
        minimumWidth=px(width)
    }
    fun label(value: String,size: Float,color: Int=text,weight: Int=400)=TextView(context).apply {
        this.text=value;setTextSize(TypedValue.COMPLEX_UNIT_DIP,OwnerTypography.size(size));setTextColor(color)
        typeface=Typeface.create(Typeface.create("sans-serif",Typeface.NORMAL),weight,false);includeFontPadding=false;gravity=Gravity.CENTER_VERTICAL
        setSingleLine(true);ellipsize=android.text.TextUtils.TruncateAt.END
    }
    fun icon(id: Int,color: Int,size: Int=20)=ImageView(context).apply {
        tag=OwnerIcon(id,size)
        setImageResource(id);imageTintList=android.content.res.ColorStateList.valueOf(color);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams=LinearLayout.LayoutParams(px(size),px(size))
    }
    fun card(padding: Boolean=true)=column().apply {
        background=shadow(shape(card,18f,line),18f,if(dark)30f else 10f,if(dark)10f else 2f,if(dark)-12f else 0f,if(dark)0x8c000000.toInt() else 0x0d0f172a)
        if(padding)setPadding(px(20),px(18),px(20),px(18))
    }
    fun gap(height: Int=14)=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=px(height) }
    fun chip(value: String,tone: Int=0,large: Boolean=false)=label(value,if(large)11f else 10f,chipFg[tone],700).apply {
        ellipsize=null;gravity=Gravity.CENTER
        background=shape(chipBg[tone],if(large)6f else 5f);setPadding(px(if(large)9 else 7),0,px(if(large)9 else 7),0)
        minimumHeight=px(if(large)22 else 18)
        layoutParams=LinearLayout.LayoutParams(-2,px(if(large)22 else 18))
    }
    fun press(v: View,scale: Float=.97f) {
        v.setOnTouchListener { view,e ->
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(smooth).start()
            };false
        }
    }
    fun button(value: String,kind: String="ghost",small: Boolean=false,icon: Int?=null,enabled: Boolean=true,action:()->Unit): View = row().apply {
        tag="owner-button"
        val color=when(kind){"primary","danger-fill"->Color.WHITE;"danger","warn-outline"->inks[3];else->sub}
        background=shape(when(kind){"primary"->accent;"danger-fill"->tiers[3];"danger","warn-outline"->Color.TRANSPARENT;else->card},if(small)10f else 12f,when(kind){"ghost"->line2;"warn-outline"->soft(tiers[3],115);else->null})
        if(kind=="dashed")background=shape(Color.TRANSPARENT,10f).apply{setStroke(px(1),line2,px(4).toFloat(),px(4).toFloat())}
        val pad=if(kind=="danger")8 else if(small)14 else 20;setPadding(px(pad),0,px(pad),0);gravity=Gravity.CENTER
        if(icon!=null)addView(icon(icon,color,16),LinearLayout.LayoutParams(px(16),px(16)).apply{marginEnd=px(7)})
        addView(label(value,if(small)12f else 13f,color,800).apply{ellipsize=null;gravity=Gravity.CENTER});minimumHeight=px(if(small)32 else 40)
        isEnabled=enabled;alpha=if(enabled)1f else .4f;isFocusable=true;contentDescription=value
        if(kind=="primary")background=shadow(checkNotNull(background),if(small)10f else 12f,18f,8f,-6f,accentGlow)
        setOnClickListener { action() };press(this,.96f)
    }
    fun title(title: String,subtitle: String,leading: View?=null,trailing: View?=null): View=row().apply {
        if(leading!=null)addView(leading,LinearLayout.LayoutParams(leading.layoutParams?.width ?: -2,leading.layoutParams?.height ?: -2).apply{marginEnd=px(14)})
        addView(column().apply {
            addView(label(title,20f,text,800),LinearLayout.LayoutParams(-1,px(27)))
            addView(label(subtitle,11.5f,muted),LinearLayout.LayoutParams(-1,px(15)).apply{topMargin=px(4)})
        },LinearLayout.LayoutParams(0,px(46),1f))
        if(trailing!=null)addView(trailing,LinearLayout.LayoutParams(-2,-2).apply{marginStart=px(14)})
        layoutParams=gap()
    }
    /** Literal page-head: back / icon / vertical identity / trailing chips. */
    fun identityTitle(title:String,pkg:String,leading:View?=null,trailing:View?=null):View=row().apply {
        if(leading!=null)addView(leading,LinearLayout.LayoutParams(-2,-2).apply{marginEnd=px(14)})
        addView(column().apply {
            addView(label(title,20f,text,800),LinearLayout.LayoutParams(-1,px(27)))
            addView(label(pkg,11.5f,muted).apply {
                tag="owner-package-id";setSingleLine(false);maxLines=2;ellipsize=null
                setHorizontallyScrolling(false);breakStrategy=android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE
            },LinearLayout.LayoutParams(-1,-2).apply{topMargin=px(4)})
        },LinearLayout.LayoutParams(0,-2,1f))
        if(trailing!=null)addView(trailing,LinearLayout.LayoutParams(-2,-2).apply{marginStart=px(14)})
        minimumHeight=px(48)
        layoutParams=gap()
    }
    fun back(description:String="返回",action:()->Unit)=icon(R.drawable.owner_back,sub,18).apply {
        background=shape(card,11f,line);setPadding(px(9),px(9),px(9),px(9))
        layoutParams=LinearLayout.LayoutParams(px(36),px(36));isFocusable=true;contentDescription=description
        setOnClickListener{action()};press(this)
    }
    fun modeChip(value:String,tier:Int)=row().apply {
        background=shape(chipBg[tier+1],999f);setPadding(px(11),0,px(13),0);minimumHeight=px(30)
        addView(OwnerPing(context,this@OwnerUi,tiers[tier]),LinearLayout.LayoutParams(px(7),px(7)).apply{marginEnd=px(8)})
        addView(label(value,12f,chipFg[tier+1],800));layoutParams=LinearLayout.LayoutParams(-2,px(30))
    }
    fun domainRow(title:String,subtitle:String,icon:Int,tone:String="zo",small:Boolean=false,action:()->Unit):View=row().apply {
        val size=if(small)34 else 40;val glyph=if(small)17 else 20
        val fg=when(tone){"mute"->muted;"warn"->chipFg[3];else->zoFg}
        val bg=when(tone){"mute"->card2;"warn"->chipBg[3];else->zoBg}
        setPadding(0,px(if(small)6 else 0),0,px(if(small)6 else 0));minimumHeight=px(if(small)46 else 40)
        addView(icon(icon,fg,glyph).apply{background=shape(bg,if(small)10f else 12f);val p=px((size-glyph)/2f);setPadding(p,p,p,p)},LinearLayout.LayoutParams(px(size),px(size)).apply{marginEnd=px(if(small)12 else 14)})
        addView(column().apply{
            addView(label(title,if(small)13f else 14f,if(small)accent else text,800),LinearLayout.LayoutParams(-1,if(small)-2 else px(19)))
            addView(label(subtitle,11f,muted),LinearLayout.LayoutParams(-1,if(small)-2 else px(15)).apply{topMargin=px(if(small)2 else 3)})
        },LinearLayout.LayoutParams(0,-2,1f))
        addView(icon(R.drawable.owner_chevron,muted,16).apply{alpha=.6f},LinearLayout.LayoutParams(px(16),px(16)).apply{marginStart=px(if(small)12 else 14)})
        isFocusable=true;contentDescription=title;setOnClickListener{action()};press(this)
    }
    fun section(title: String,subtitle: String,trailing: View?=null)=row().apply {
        addView(column().apply{
                    addView(label(title,14f,this@OwnerUi.text,800),LinearLayout.LayoutParams(-1,px(20)))
            addView(label(subtitle,11f,muted),LinearLayout.LayoutParams(-1,px(14)).apply{topMargin=px(4)})
        },LinearLayout.LayoutParams(0,px(38),1f))
        if(trailing!=null)addView(trailing);layoutParams=LinearLayout.LayoutParams(-1,px(38)).apply{bottomMargin=px(14)}
    }
    fun divider()=View(context).apply{setBackgroundColor(line);layoutParams=LinearLayout.LayoutParams(-1,px(1)).apply{topMargin=px(20);bottomMargin=px(18)}}
    fun search(hint: String,value: String=""): Pair<View,EditText> {
        val input=EditText(context).apply{
            this.hint=hint;setText(value);setTextSize(TypedValue.COMPLEX_UNIT_DIP,12f);setTextColor(this@OwnerUi.text);setHintTextColor(muted)
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true);includeFontPadding=false;background=null;setPadding(0,0,0,0)
        }
        return row().apply{
            background=shape(card,12f,line);setPadding(px(12),0,px(12),0)
            addView(icon(R.drawable.owner_search,muted,15),LinearLayout.LayoutParams(px(15),px(15)).apply{marginEnd=px(8)})
            addView(input,LinearLayout.LayoutParams(0,-1,1f))
        } to input
    }
    fun empty(title: String,description: String,error: Boolean=false)=column().apply{
        gravity=Gravity.CENTER;setPadding(px(24),px(24),px(24),px(24));background=shape(if(error)chipBg[3] else card,18f,line)
        addView(icon(if(error)R.drawable.owner_warn else R.drawable.owner_info,if(error)chipFg[3] else muted,24),LinearLayout.LayoutParams(px(24),px(24)).apply{bottomMargin=px(12)})
        addView(label(title,13f,if(error)chipFg[3] else sub,700).apply{setSingleLine(false);gravity=Gravity.CENTER})
        addView(label(description,11.5f,if(error)chipFg[3] else muted).apply{setSingleLine(false);gravity=Gravity.CENTER;setLineSpacing(0f,1.5f)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=px(6)})
    }
    fun formRow(title: String,control: View)=row().apply{
        setPadding(0,px(9),0,px(9))
        addView(label(title,12f,sub,700),LinearLayout.LayoutParams(px(72),-2).apply{marginEnd=px(12)})
        addView(control,LinearLayout.LayoutParams(0,-2,1f));minimumHeight=px(52)
    }
    fun settingsRow(title:String,description:String,trailing:View)=row().apply{
        minimumHeight=px(46);setPadding(0,px(6),0,px(6))
        addView(column().apply{
            addView(label(title,13f,text,800));addView(label(description,11f,muted),LinearLayout.LayoutParams(-1,px(14)).apply{topMargin=px(2)})
        },LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=px(12)})
        addView(trailing)
    }
    fun tableRow(cells:List<View>,widths:List<Int>,header:Boolean=false)=column().apply{
        addView(row().apply{
            setPadding(px(7),px(10),px(7),px(10));minimumHeight=px(40)
            cells.forEachIndexed{i,cell->addView(cell,if(widths[i]==0)LinearLayout.LayoutParams(0,-2,1f) else LinearLayout.LayoutParams(px(widths[i]),-2))}
        },LinearLayout.LayoutParams(-1,-2))
        addView(View(context).apply{setBackgroundColor(if(header)line2 else line)},LinearLayout.LayoutParams(-1,px(1)))
    }
    fun scroll(content: View,height: Int)=ScrollView(context).apply{
        isVerticalScrollBarEnabled=false;clipChildren=true;clipToPadding=true
        addOnLayoutChangeListener{v,_,_,_,_,_,_,_,_->v.clipBounds=Rect(0,0,v.width,v.height)}
        addView(content);layoutParams=LinearLayout.LayoutParams(-1,px(height))
        minimumHeight=px(height)
    }
    fun cpus(initial: Set<Int>,large:Boolean=false,action:(Set<Int>)->Boolean)=OwnerCpuPicker(context,this,initial,large,action)
    fun check(value:String,checked:Boolean=false,action:(Boolean)->Unit):View=row().apply{
        var selected=checked;setPadding(px(6),px(8),px(6),px(8));isFocusable=true
        val mark=label(if(selected)"✓" else "",12f,Color.WHITE,700).apply{gravity=Gravity.CENTER;background=shape(if(selected)accent else Color.TRANSPARENT,4f,if(selected)accent else line2,2f)}
        addView(mark,LinearLayout.LayoutParams(px(16),px(16)).apply{marginEnd=px(10)})
        addView(label(value,12f,text,600).apply{setSingleLine(false);setLineSpacing(0f,1.5f)},LinearLayout.LayoutParams(0,-2,1f))
        fun describe(){contentDescription="$value，${if(selected)"已选择" else "未选择"}";isSelected=selected}
        describe();setOnClickListener{selected=!selected;mark.text=if(selected)"✓" else "";mark.background=shape(if(selected)accent else Color.TRANSPARENT,4f,if(selected)accent else line2,2f);background=shape(if(selected)soft(accent) else Color.TRANSPARENT,8f);describe();action(selected)}
    }
    fun tiers(current: String,compact: Boolean=false,enabled: Boolean=true,reconcile:(((String)->Unit)->Unit)?=null,action:(String)->Unit)=row().apply {
        tag="owner-control"
        val group=this
        var selectedIndex=GpuDefaultsDraft.modes.indexOf(current)
        GpuDefaultsDraft.modes.forEachIndexed { i,id ->
            val selected=id==current
            val item=row().apply{
                gravity=Gravity.CENTER;background=shape(if(selected)tiers[i] else card2,14f)
                if(selected)background=shadow(checkNotNull(background),14f,22f,10f,-10f,tiers[i])
                addView(OwnerGauge(context,this@OwnerUi,i,selected),LinearLayout.LayoutParams(px(if(compact)28 else 34),px(if(compact)28 else 34)).apply{marginEnd=px(if(compact)8 else 10)})
                addView(column().apply{
                    addView(label(names[i],14f,if(selected)Color.WHITE else text,800),LinearLayout.LayoutParams(-2,px(18)))
                    if(!compact)addView(label(descriptions[i],11f,if(selected)0xd1ffffff.toInt() else muted,600),LinearLayout.LayoutParams(-2,px(14)).apply{topMargin=px(2)})
                })
                isEnabled=enabled;alpha=if(enabled)1f else .6f;isFocusable=true;contentDescription="${names[i]}${if(selected)"，已选择" else ""}"
                setOnClickListener{
                    if(i!=selectedIndex){
                        val previous=selectedIndex;selectedIndex=i
                        for(k in 0 until group.childCount){
                            val target=group.getChildAt(k) as LinearLayout;val lit=k==i
                            val start=if(k==previous)tiers[k] else card2;val end=if(lit)tiers[k] else card2
                            ValueAnimator.ofArgb(start,end).apply{duration=250;addUpdateListener{val surface=shape(it.animatedValue as Int,14f);target.background=if(lit)shadow(surface,14f,22f,10f,-10f,tiers[k]) else surface};start()}
                            (target.getChildAt(0) as OwnerGauge).animateInk(lit)
                            val textColumn=target.getChildAt(1) as LinearLayout
                            fun ink(label:TextView,end:Int){val start=label.currentTextColor;ValueAnimator.ofArgb(start,end).apply{duration=250;addUpdateListener{label.setTextColor(it.animatedValue as Int)};start()}}
                            ink(textColumn.getChildAt(0) as TextView,if(lit)Color.WHITE else text)
                            if(!compact)ink(textColumn.getChildAt(1) as TextView,if(lit)0xd1ffffff.toInt() else muted)
                            target.contentDescription="${names[k]}${if(lit)"，已选择" else ""}"
                        }
                        action(id)
                    }
                };press(this)
            }
            addView(item,LinearLayout.LayoutParams(0,px(if(compact)46 else 64),1f).apply{if(i>0)marginStart=px(10)})
        }
        reconcile?.invoke{mode->
            val index=GpuDefaultsDraft.modes.indexOf(mode)
            if(index!=selectedIndex){selectedIndex=index
                for(k in 0 until group.childCount){
                    val target=group.getChildAt(k) as LinearLayout;val lit=k==index
                    target.background=shape(if(lit)tiers[k] else card2,14f)
                    if(lit)target.background=shadow(checkNotNull(target.background),14f,22f,10f,-10f,tiers[k])
                    (target.getChildAt(0) as OwnerGauge).animateInk(lit)
                    val labels=target.getChildAt(1) as LinearLayout
                    (labels.getChildAt(0) as TextView).setTextColor(if(lit)Color.WHITE else text)
                    if(!compact)(labels.getChildAt(1) as TextView).setTextColor(if(lit)0xd1ffffff.toInt() else muted)
                    target.contentDescription="${names[k]}${if(lit)"，已选择" else ""}"
                }
            }
        }
    }
    companion object {
        val spring=PathInterpolator(.34f,1.56f,.64f,1f)
        val smooth=PathInterpolator(.16f,1f,.3f,1f)
        val names=listOf("节能","均衡","性能","快速")
        val descriptions=listOf("温控优先","默认基准","升频优先","竞技拉满")
    }
}

/** Uniform viewport transform keeps all Owner CSS proportions on the 16:10 device. */
internal class OwnerDesignLayout(context: Context): FrameLayout(context) {
    private val unit=resources.displayMetrics.density
    override fun onMeasure(w: Int,h: Int) {
        val width=MeasureSpec.getSize(w);val height=MeasureSpec.getSize(h);setMeasuredDimension(width,height)
        val dw=(1040*unit).roundToInt()
        val scale=min(width.toFloat()/dw,height.toFloat()/(650*unit))
        val dh=(650*unit).roundToInt()
        for(i in 0 until childCount)getChildAt(i).apply{
            measure(MeasureSpec.makeMeasureSpec(dw,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(dh,MeasureSpec.EXACTLY))
            pivotX=0f;pivotY=0f;scaleX=scale;scaleY=scale
        }
    }
    override fun onLayout(changed: Boolean,l: Int,t: Int,r: Int,b: Int) {
        for(i in 0 until childCount)getChildAt(i).apply{
            val x=0;val y=0
            layout(x,y,x+measuredWidth,y+measuredHeight)
        }
    }
}

@SuppressLint("ViewConstructor")
internal class OwnerSegment(context: Context,private val ui: OwnerUi,private val values: List<String>,selected: Int,
    private val small: Boolean=false,enabled: Boolean=true,private val action:(Int)->Unit): FrameLayout(context) {
    private var position=selected.toFloat();private var index=selected;private var animator: ValueAnimator?=null
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val options=ui.row()
    init {
        setWillNotDraw(false);background=ui.shape(ui.card2,if(small)10f else 14f);isEnabled=enabled;alpha=if(enabled)1f else .6f
        val pad=ui.px(if(small)3 else 4);options.setPadding(pad,pad,pad,pad)
        values.forEachIndexed { i,value ->
            val text=ui.label(value,if(small)12f else 15f,if(i==index)Color.WHITE else ui.sub,if(small)700 else 800).apply{
                ellipsize=null
                gravity=Gravity.CENTER;isFocusable=true;contentDescription=value;isEnabled=enabled
                if(value.endsWith(" Hz")) {
                    val s=android.text.SpannableString(value.replace(" Hz","Hz"));s.setSpan(android.text.style.RelativeSizeSpan(10f/15f),s.length-2,s.length,0)
                    s.setSpan(android.text.style.ForegroundColorSpan(if(i==index)0xd9ffffff.toInt() else ui.soft(ui.sub,153)),s.length-2,s.length,0);setText(s,TextView.BufferType.SPANNABLE)
                }
                setOnClickListener{
                    if(i==index)return@setOnClickListener
                    showSelection(i)
                    action(i)
                }
            }
            options.addView(text,LinearLayout.LayoutParams(0,-1,1f).apply{if(i>0)marginStart=ui.px(if(small)2 else 4)})
        }
        val measure=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=ui.px(if(small)OwnerTypography.BUTTON.size else 15f).toFloat();typeface=Typeface.create("sans-serif",Typeface.BOLD)}
        minimumWidth=((values.maxOfOrNull{measure.measureText(it)} ?: 0f)+ui.px(if(small)16 else 24)).roundToInt()*values.size+pad*2+ui.px(if(small)2 else 4)*(values.size-1).coerceAtLeast(0)
        addView(options,LayoutParams(-1,-1));minimumHeight=ui.px(if(small)34 else 50)
        layoutParams=LinearLayout.LayoutParams(-1,ui.px(if(small)34 else 50))
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
        // HorizontalScrollView measures with an unbounded width. Keep the labels
        // on the same final geometry as the indicator, including intrinsic width.
        options.measure(MeasureSpec.makeMeasureSpec((measuredWidth-paddingLeft-paddingRight).coerceAtLeast(0),MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((measuredHeight-paddingTop-paddingBottom).coerceAtLeast(0),MeasureSpec.EXACTLY))
    }
    fun showSelection(i:Int){
        if(i==index)return
        animator?.cancel();animator=ValueAnimator.ofFloat(position,i.toFloat()).apply{
            duration=350;interpolator=OwnerUi.spring;addUpdateListener{position=it.animatedValue as Float;invalidate()};start()
        }
        index=i;for(k in 0 until options.childCount){
            val label=options.getChildAt(k) as TextView;label.setTextColor(if(k==i)Color.WHITE else ui.sub)
            (label.text as? android.text.Spannable)?.let { s ->
                s.getSpans(0,s.length,android.text.style.ForegroundColorSpan::class.java).forEach(s::removeSpan)
                s.setSpan(android.text.style.ForegroundColorSpan(if(k==i)0xd9ffffff.toInt() else ui.soft(ui.sub,153)),s.length-2,s.length,0)
            }
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas);if(index<0)return
        val p=ui.px(if(small)3 else 4).toFloat();val gap=ui.px(if(small)2 else 4).toFloat()
        val w=(width-2*p-(values.size-1)*gap)/values.size
        paint.color=ui.accent;paint.setShadowLayer(ui.px(16).toFloat(),0f,ui.px(6).toFloat(),ui.accentGlow)
        val left=p+position*(w+gap);val spread=ui.px(6).toFloat()
        canvas.drawRoundRect(left+spread,p+spread,left+w-spread,height-p-spread,ui.px(5).toFloat(),ui.px(5).toFloat(),paint);paint.clearShadowLayer()
        canvas.drawRoundRect(left,p,left+w,height-p,ui.px(if(small)8 else 11).toFloat(),ui.px(if(small)8 else 11).toFloat(),paint)
    }
    override fun onDetachedFromWindow(){animator?.cancel();super.onDetachedFromWindow()}
}

/** Literal .ping / .ping::after, presentation only. */
@SuppressLint("ViewConstructor")
internal class OwnerPing(context: Context,private val ui: OwnerUi,private var tone: Int):FrameLayout(context) {
    private val dot=View(context)
    private val motion=(context.getDrawable(R.drawable.owner_ping_halo) as AnimatedVectorDrawable).apply{mutate()}
    private val halo=ImageView(context).apply{setImageDrawable(motion)}
    private var pulsing=false
    private var ready=false
    init {
        clipChildren=false;clipToPadding=false
        addView(dot,LayoutParams(ui.px(7),ui.px(7),Gravity.CENTER))
        addView(halo,LayoutParams(ui.px(7)*3,ui.px(7)*3,Gravity.CENTER));applyTone();ready=true
    }
    private fun applyTone(){
        dot.background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(tone)}
        motion.setTint(tone)
    }
    fun setTone(color:Int){if(tone!=color){tone=color;applyTone()}}
    fun retheme(color:(Int)->Int){setTone(color(tone))}
    private fun updatePulse(){
        if(!ready)return
        val next=isAttachedToWindow && windowVisibility==VISIBLE && isShown
        if(next==pulsing)return
        pulsing=next
        if(next)motion.start()else motion.stop()
    }
    override fun onLayout(changed:Boolean,left:Int,top:Int,right:Int,bottom:Int){
        super.onLayout(changed,left,top,right,bottom)
        halo.translationX=(width-halo.width)/2f-halo.left;halo.translationY=(height-halo.height)/2f-halo.top
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();updatePulse()}
    override fun onWindowVisibilityChanged(visibility:Int){super.onWindowVisibilityChanged(visibility);updatePulse()}
    override fun onVisibilityChanged(changedView:View,visibility:Int){super.onVisibilityChanged(changedView,visibility);updatePulse()}
    override fun onDetachedFromWindow(){pulsing=false;motion.stop();super.onDetachedFromWindow()}
}

@SuppressLint("ViewConstructor")
internal class OwnerGauge(context: Context,private val ui: OwnerUi,private val tier: Int,selected: Boolean=false):View(context) {
    private var litAmount=if(selected)1f else 0f
    private var inkMotion:ValueAnimator?=null
    fun animateInk(value:Boolean){inkMotion?.cancel();inkMotion=ValueAnimator.ofFloat(litAmount,if(value)1f else 0f).apply{duration=250;addUpdateListener{litAmount=it.animatedValue as Float;invalidate()};start()}}
    private fun ink(off:Int,on:Int)=android.animation.ArgbEvaluator().evaluate(litAmount,off,on) as Int
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{strokeCap=Paint.Cap.ROUND}
    override fun onDraw(c: Canvas) {
        val save=c.save();c.scale(width/32f,height/32f)
        val level=(tier+1)/4f;val oval=RectF(5f,7.5f,27f,29.5f)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=2.6f;paint.color=ink(ui.line2,0x47ffffff);c.drawArc(oval,150f,240f,false,paint)
        paint.color=ink(ui.tiers[tier],Color.WHITE);c.drawArc(oval,150f,240*level,false,paint)
        paint.strokeWidth=1.4f;paint.color=ink(ui.muted,0x99ffffff.toInt())
        fun point(deg: Float,r: Float)=Pair(16f+r*cos(deg*PI/180).toFloat(),18.5f-r*sin(deg*PI/180).toFloat())
        for(i in 0..4){val d=210-60f*i;val a=point(d,6.4f);val b=point(d,8.2f);c.drawLine(a.first,a.second,b.first,b.second,paint)}
        val tip=point(210-240*level,7.6f);paint.strokeWidth=2.2f;paint.color=ink(ui.text,Color.WHITE)
        c.drawLine(16f,18.5f,tip.first,tip.second,paint);paint.style=Paint.Style.FILL;c.drawCircle(16f,18.5f,2.2f,paint);c.restoreToCount(save)
    }
    override fun onDetachedFromWindow(){inkMotion?.cancel();super.onDetachedFromWindow()}
}

@SuppressLint("ViewConstructor")
internal class OwnerMeter(context: Context,private val ui: OwnerUi,private val cuts: List<Float> = emptyList(),private var segments: List<Int>? = null): View(context) {
    fun showSegments(value:List<Int>){if(segments!=value){segments=value;invalidate()}}
    fun retheme(color:(Int)->Int){tone=color(tone);segments=segments?.map(color);invalidate()}
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG);private var shown=0f;private var animator: ValueAnimator?=null
    private var segmentTime=0f
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(segments!=null)animator=ValueAnimator.ofFloat(0f,840f).apply{duration=840;addUpdateListener{segmentTime=it.animatedValue as Float;invalidate()};start()}}
    var tone=ui.accent
    fun setFraction(value: Float) {
        val next=value.coerceIn(0f,1f);if(abs(next-shown)<.002f)return
        animator?.cancel();animator=ValueAnimator.ofFloat(shown,next).apply{duration=900;interpolator=OwnerUi.smooth;addUpdateListener{shown=it.animatedValue as Float;invalidate()};start()}
    }
    override fun onDraw(c: Canvas) {
        val h=height.toFloat();val r=ui.px(3).toFloat()
        val parts=segments
        if(parts!=null){val w=(width-ui.px(4)*(parts.size-1)).toFloat()/parts.size;parts.forEachIndexed{i,color->paint.color=color;val l=i*(w+ui.px(4));val f=OwnerUi.smooth.getInterpolation(((segmentTime-i*60)/600f).coerceIn(0f,1f));c.drawRoundRect(l,0f,l+w*f,h,r,r,paint)};return}
        paint.color=ui.card2;c.drawRoundRect(0f,0f,width.toFloat(),h,r,r,paint)
        paint.color=tone;c.drawRoundRect(0f,0f,width*shown,h,r,r,paint)
        paint.color=ui.card;cuts.forEach{val x=it*width;c.drawRect(x-ui.px(1),0f,x+ui.px(1),h,paint)}
    }
    override fun onDetachedFromWindow(){animator?.cancel();super.onDetachedFromWindow()}
}

/** Owner .modal/.sheet: in-shell so the OS dialog decor cannot change geometry. */
internal class OwnerModal(private val ui: OwnerUi,private val host: FrameLayout,private val content: View) {
    private var overlay: FrameLayout?=null
    private var onDismiss:(()->Unit)?=null
    val isOpen get()=overlay!=null
    fun close(){
        val current=overlay?:return;overlay=null
        val callback=onDismiss;onDismiss=null;callback?.invoke()
        if(Build.VERSION.SDK_INT>=31)content.setRenderEffect(null)
        current.animate().alpha(0f).setDuration(200).withEndAction{host.removeView(current)}.start()
    }
    fun open(title: String,subtitle: String,width: Int=440,body: View,buttons: List<View>,onDismiss:(()->Unit)?=null) {
        close()
        this.onDismiss=onDismiss
        if(Build.VERSION.SDK_INT>=31)content.setRenderEffect(RenderEffect.createBlurEffect(ui.px(3).toFloat(),ui.px(3).toFloat(),Shader.TileMode.CLAMP))
        val mask=FrameLayout(ui.context).apply{clipChildren=false;setBackgroundColor(0x8c03060c.toInt());isClickable=true;setOnClickListener{close()}}
        val sheet=ui.column().apply{background=ui.shadow(ui.shape(ui.card,20f,ui.line2),20f,80f,30f,0f,0x73000000);setOnClickListener{};isClickable=true}
        sheet.addView(ui.row().apply{
            setPadding(ui.px(20),ui.px(18),ui.px(20),ui.px(12));gravity=Gravity.TOP
            addView(ui.column().apply{
                addView(ui.label(title,16f,ui.text,800),LinearLayout.LayoutParams(-1,ui.px(21)))
                if(subtitle.isNotEmpty())addView(ui.label(subtitle,11.5f,ui.muted).apply{setSingleLine(false)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=ui.px(4)})
            },LinearLayout.LayoutParams(0,-2,1f))
            addView(ui.icon(R.drawable.owner_close,ui.sub,16).apply{background=ui.shape(ui.card2,9f);setPadding(ui.px(7),ui.px(7),ui.px(7),ui.px(7));isFocusable=true;contentDescription="关闭";setOnClickListener{close()}},LinearLayout.LayoutParams(ui.px(30),ui.px(30)).apply{marginStart=ui.px(12)})
        })
        sheet.addView(body,LinearLayout.LayoutParams(-1,if(body is ScrollView && body.minimumHeight>0)body.minimumHeight else -2).apply{marginStart=ui.px(20);marginEnd=ui.px(20)})
        sheet.addView(ui.row().apply{gravity=Gravity.END;setPadding(ui.px(20),ui.px(14),ui.px(20),ui.px(18));buttons.forEach{addView(it,LinearLayout.LayoutParams(-2,ui.px(40)).apply{marginStart=ui.px(10)})}})
        mask.addView(sheet,FrameLayout.LayoutParams(ui.px(width),-2,Gravity.CENTER));host.addView(mask,FrameLayout.LayoutParams(-1,-1));overlay=mask
        mask.alpha=0f;mask.animate().alpha(1f).setDuration(250).start()
        sheet.alpha=0f;sheet.translationY=ui.px(14).toFloat();sheet.scaleX=.97f;sheet.scaleY=.97f
        sheet.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(350).setInterpolator(OwnerUi.spring).start()
    }
}
