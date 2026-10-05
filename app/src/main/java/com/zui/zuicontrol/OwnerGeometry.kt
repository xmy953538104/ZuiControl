package com.zui.zuicontrol

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in, finite visual qualification trace. Enable with log.tag.OwnerGeometry=VERBOSE. */
internal object OwnerGeometry {
    private const val TAG="OwnerGeometry"
    private fun enabled()=Log.isLoggable(TAG,Log.VERBOSE)
    private fun transforms(view:View):JSONArray {
        val result=JSONArray();var current:View?=view
        while(current!=null) {
            val node=current
            if(node.scaleX!=1f || node.scaleY!=1f)result.put(JSONArray(listOf(node.javaClass.simpleName,node.scaleX,node.scaleY)))
            current=node.parent as? View
        }
        return result
    }
    fun composition(root:View,top:Int,bottom:Int) {
        if(!enabled())return
        val bounds=JSONArray()
        val row=((root as? ViewGroup)?.getChildAt(0) as? ViewGroup)?.getChildAt(0) as? ViewGroup
        if(row!=null)for(i in 0 until row.childCount) {
            val child=row.getChildAt(i);val origin=IntArray(2);child.getLocationOnScreen(origin)
            val scale=minOf(root.width/1040f,root.height/650f)/root.resources.displayMetrics.density
            bounds.put(JSONObject().put("column",i).put("physicalBounds",JSONArray(listOf(origin[0],origin[1],origin[0]+child.width*scale,origin[1]+child.height*scale))))
        }
        Log.v(TAG,JSONObject().put("kind","composition").put("physical",JSONArray(listOf(root.width,root.height)))
            .put("insets",JSONArray(listOf(top,bottom))).put("logical",JSONArray(listOf(1040,650)))
            .put("scale",minOf(root.width/1040f,root.height/650f))
            .put("rootPadding",JSONArray(listOf(root.paddingLeft,root.paddingTop,root.paddingRight,root.paddingBottom))).put("columns",bounds).toString())
    }
    fun theme(root:View,fraction:Int,tree:String) {
        if(!enabled())return
        fun visit(view:View,path:String) {
            if(view is TextView && view.text.toString() !in listOf("浅色","深色")) {
                val location=IntArray(2);view.getLocationOnScreen(location)
                Log.v(TAG,JSONObject().put("kind","theme").put("fraction",fraction).put("tree",tree).put("path",path)
                    .put("textHash",view.text.toString().hashCode()).put("bounds",JSONArray(listOf(view.left,view.top,view.right,view.bottom)))
                    .put("physicalOrigin",JSONArray(location.toList())).put("textSize",view.textSize)
                    .put("scale",JSONArray(listOf(view.scaleX,view.scaleY))).put("translation",JSONArray(listOf(view.translationX,view.translationY)))
                    .put("lineHeight",view.lineHeight).put("weight",view.typeface.weight).put("ancestorTransforms",transforms(view)).toString())
            }
            if(view is ViewGroup)for(i in 0 until view.childCount)visit(view.getChildAt(i),"$path/$i")
        }
        visit(root,"root")
    }
}
