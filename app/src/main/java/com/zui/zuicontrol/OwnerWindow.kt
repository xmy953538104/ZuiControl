package com.zui.zuicontrol

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RenderNode
import android.graphics.HardwareRenderer
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.ViewGroup
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Shared presentation only; no policy, producer or command ownership. */
internal object OwnerWindow {
    private data class Safe(val top:Float,val bottom:Float,val height:Int)
    private val safe=WeakHashMap<View,Safe>()
    fun safeContent(view:View,top:Float,bottom:Float=0f) {
        safe[view]=Safe(top,bottom,view.layoutParams?.height ?: -2)
    }
    fun themed(base:Context):Context {
        val theme=base.getSharedPreferences("frontend",Context.MODE_PRIVATE).getString("theme","system").orEmpty()
        return if(theme=="system")base else base.createConfigurationContext(Configuration(base.resources.configuration).apply{
            uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if(FrontendTheme.dark(theme,false))Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        })
    }
    /** Activity creation only. Theme changes use appearance without window setup. */
    fun fullscreen(activity:Activity)=with(activity.window){
        statusBarColor=Color.TRANSPARENT;navigationBarColor=Color.TRANSPARENT
        // Preserve the current bar ink while changing layout flags. The single
        // appearance update below owns the next ink; clearing it first flashes.
        val inkMask=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        decorView.systemUiVisibility=(decorView.systemUiVisibility and inkMask) or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if(Build.VERSION.SDK_INT>=30){
            setDecorFitsSystemWindows(false)
            insetsController?.show(WindowInsets.Type.systemBars())
        }
        isStatusBarContrastEnforced=false;isNavigationBarContrastEnforced=false
        updateSystemBarAppearance(activity,dark(activity))
    }
    /** Ink only: preserve the existing viewport, visibility, layout and insets. */
    fun updateSystemBarAppearance(activity:Activity,dark:Boolean)=with(activity.window){
        if(Build.VERSION.SDK_INT>=30){
            val mask=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            insetsController?.setSystemBarsAppearance(if(dark)0 else mask,mask)
        }else{
            val mask=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            decorView.systemUiVisibility=(decorView.systemUiVisibility and mask.inv()) or if(dark)0 else mask
        }
    }
    /** Publish one native GPU-rendered target frame, then expose the same retained tree. */
    fun transitionSystemBars(activity:Activity,host:View,target:View,dark:Boolean,palette:()->Unit,finished:()->Unit){
        fun committed(action:()->Unit){
            if(Build.VERSION.SDK_INT>=29 && host.isHardwareAccelerated)host.viewTreeObserver.registerFrameCommitCallback{host.post{action()}}
            else host.postOnAnimation{action()}
        }
        val source=Bitmap.createBitmap(host.width,host.height,Bitmap.Config.ARGB_8888)
        fun copied(result:Int){
            if(activity.isDestroyed || !host.isAttachedToWindow){source.recycle();return}
            OwnerRenderTrace.event("THEME_SOURCE_COPY_RESULT","result=$result")
            val cover=if(result==PixelCopy.SUCCESS)BitmapDrawable(host.resources,source).apply{
                setBounds(0,0,host.width,host.height)
            }else null
            cover?.let{host.overlay.add(it)}
            val previousLayer=target.layerType
            if(target.isHardwareAccelerated)target.setLayerType(View.LAYER_TYPE_HARDWARE,null)
            palette()
            if(target.isHardwareAccelerated)target.buildLayer()
            committed {
                OwnerRenderTrace.event("STATUS_SOURCE_FRAME_COMMITTED","dark=$dark")
                // Record actual native View/RenderNode drawing at window pixel
                // resolution. The temporary source removal has no intervening
                // window traversal; its source buffer stays on the display.
                cover?.let{host.overlay.remove(it)}
                val targetFrame=try{nativeFrame(host)}finally{cover?.let{host.overlay.add(it)}}
                OwnerRenderTrace.event("THEME_TARGET_COPY_RESULT","success=${targetFrame!=null}")
                val targetCover=targetFrame?.let{BitmapDrawable(host.resources,it).apply{setBounds(0,0,host.width,host.height)}}
                host.postOnAnimation {
                    updateSystemBarAppearance(activity,dark)
                    OwnerRenderTrace.event("STATUS_INK_REQUEST","dark=$dark;sourceSubmitted=true")
                    committed {
                        OwnerRenderTrace.event("STATUS_APPEARANCE_FRAME_COMMITTED","dark=$dark")
                    }
                    // Frame-commit delivery may run after the next animation
                    // callback. Publish from Choreographer itself, independent
                    // of callback delivery and retained page render cost.
                    host.postOnAnimation {
                        OwnerRenderTrace.event("THEME_PUBLISH_BOUNDARY","dark=$dark")
                        targetCover?.let{host.overlay.add(it)}
                        cover?.let{host.overlay.remove(it)}
                        committed {
                            OwnerRenderTrace.event("THEME_APP_FRAME_COMMITTED","dark=$dark")
                            target.setLayerType(previousLayer,null)
                            targetCover?.let{host.overlay.remove(it)}
                            committed {host.postOnAnimation {source.recycle();targetFrame?.recycle();finished()}}
                            host.invalidate()
                        }
                        host.invalidate()
                    }
                    host.invalidate()
                }
            }
            host.invalidate()
        }
        val location=IntArray(2);host.getLocationInWindow(location)
        try{
            PixelCopy.request(activity.window,Rect(location[0],location[1],location[0]+host.width,location[1]+host.height),
                source,::copied,Handler(Looper.getMainLooper()))
        }catch(_:IllegalArgumentException){copied(PixelCopy.ERROR_SOURCE_INVALID)}
    }
    /** Standard hardware renderer; no software Canvas raster or new product View. */
    private fun nativeFrame(host:View):Bitmap? {
        if(Build.VERSION.SDK_INT<29 || !host.isHardwareAccelerated)return null
        val reader=ImageReader.newInstance(host.width,host.height,PixelFormat.RGBA_8888,2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val renderer=HardwareRenderer()
        val node=RenderNode("OwnerThemeFrame").apply{setPosition(0,0,host.width,host.height)}
        try{
            val canvas=node.beginRecording()
            try{host.draw(canvas)}finally{node.endRecording()}
            renderer.setSurface(reader.surface);renderer.setContentRoot(node)
            val result=renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            if(result!=HardwareRenderer.SYNC_OK)return null
            val image=reader.acquireNextImage() ?: return null
            try{
                val buffer=image.hardwareBuffer ?: return null
                try{return Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB))}
                finally{buffer.close()}
            }finally{image.close()}
        }catch(e:RuntimeException){
            OwnerRenderTrace.event("THEME_TARGET_COPY_ERROR",e.javaClass.simpleName);return null
        }finally{renderer.destroy();node.discardDisplayList();reader.close()}
    }
    fun dark(context:Context):Boolean=FrontendTheme.dark(
        context.getSharedPreferences("frontend",Context.MODE_PRIVATE).getString("theme","system").orEmpty(),
        context.applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES)
    fun inset(view:View){
        view.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(v:View){v.requestApplyInsets()}
            override fun onViewDetachedFromWindow(v:View){}
        })
        view.setOnApplyWindowInsetsListener{v,insets->
            val top:Int;val bottom:Int
            if(Build.VERSION.SDK_INT>=30){
                val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                top=bars.top;bottom=bars.bottom
            }else{top=insets.systemWindowInsetTop;bottom=insets.systemWindowInsetBottom}
            // Insets affect only existing content whitespace, never the Owner viewport.
            v.setPadding(0,0,0,0)
            val density=v.resources.displayMetrics.density
            val width=if(v.width>0)v.width else v.rootView.width
            val height=if(v.height>0)v.height else v.rootView.height
            val logicalScale=min(width/1040f,height/650f).coerceAtLeast(.01f)
            fun apply(target:View) {
                safe[target]?.let{s->
                    val extra=max(0f,top/logicalScale-s.top+2f)
                    val b=if(s.bottom>0f)max(s.bottom,bottom/logicalScale+2f)else target.paddingBottom/density
                    target.setPadding(target.paddingLeft,((s.top+extra)*density).roundToInt(),target.paddingRight,(b*density).roundToInt())
                    if(s.height>0)target.layoutParams=target.layoutParams.apply{this.height=s.height+(extra*density).roundToInt()}
                }
                if(target is ViewGroup)for(i in 0 until target.childCount)apply(target.getChildAt(i))
            }
            if(width>0 && height>0)apply(v)else v.post{v.requestApplyInsets()}
            v.post{OwnerGeometry.composition(v,top,bottom)}
            insets
        }
        view.requestApplyInsets()
    }
}
