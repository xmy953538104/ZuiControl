package com.zui.server.control;

import android.util.SparseArray;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Objects;
import java.util.WeakHashMap;

/** Own cap at the lowest OEM priority, composed into a read copy, never the OEM store. */
public final class RenderVoteOwner {
    private static final WeakHashMap<Object,Integer> caps=new WeakHashMap<>();
    private static Adapter adapter;
    private static volatile String error="";

    public static void update(Object storage,Integer hz)throws Exception {
        if(hz!=null && hz!=60 && hz!=90 && hz!=120 && hz!=144 && hz!=165)
            throw new IllegalArgumentException("render cap");
        if(storage.getClass().getField("ZUI_CONTROL_RENDER_HOOK").getInt(null)!=1)
            throw new IllegalStateException("render hook unavailable");
        synchronized(caps) {
            if(adapter==null)adapter=new Adapter();
            if(Objects.equals(caps.get(storage),hz))return;
            if(hz==null)caps.remove(storage);else caps.put(storage,hz);
        }
        Object listener=storage.getClass().getField("mListener").get(storage);
        java.lang.reflect.Method changed=Class.forName("com.android.server.display.mode.VotesStorage$Listener").getMethod("onChanged");
        changed.invoke(listener);
    }

    public static SparseArray merge(Object storage,SparseArray votes) {
        Integer hz;Adapter layout;
        synchronized(caps){hz=caps.get(storage);layout=adapter;}
        if(hz==null)return votes;
        try {
            Object old=votes.get(0);
            votes.put(0,layout.cap(old,hz));
            error="";
        }catch(Exception e) {
            // OEM constraints win when this adapter cannot safely compose a vote.
            error=e.getClass().getSimpleName();
        }
        return votes;
    }
    static String error(){return error;}

    private static final class Adapter {
        final Class<?> vote=Class.forName("com.android.server.display.mode.Vote");
        final Constructor<?> constructor=vote.getConstructor(int.class,int.class,float.class,float.class,float.class,float.class,boolean.class,float.class);
        final Field width=vote.getField("width"),height=vote.getField("height"),ranges=vote.getField("refreshRateRanges"),disable=vote.getField("disableRefreshRateSwitching"),base=vote.getField("appRequestBaseModeRefreshRate");
        final Field physical=ranges.getType().getField("physical"),render=ranges.getType().getField("render");
        final Field min=render.getType().getField("min"),max=render.getType().getField("max");
        Adapter()throws Exception {
            if(!"PRIORITY_DEFAULT_REFRESH_RATE".equals(vote.getMethod("priorityToString",int.class).invoke(null,0)))
                throw new IllegalStateException("unsupported vote layout");
        }
        Object cap(Object old,int hz)throws Exception {
            if(old==null)return constructor.newInstance(-1,-1,0f,Float.POSITIVE_INFINITY,0f,(float)hz,false,0f);
            Object bounds=ranges.get(old),physicalRange=physical.get(bounds),renderRange=render.get(bounds);
            float lower=min.getFloat(renderRange),upper=Math.min(max.getFloat(renderRange),hz);
            if(lower>upper)return old;
            return constructor.newInstance(width.getInt(old),height.getInt(old),min.getFloat(physicalRange),max.getFloat(physicalRange),lower,upper,disable.getBoolean(old),base.getFloat(old));
        }
    }
}
