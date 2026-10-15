"""Compile the actual vote composer; concurrent OEM ownership is never overwritten."""
from pathlib import Path
import subprocess,sys,tempfile
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts/build'))
from PatchRenderVoteOwner import patch

fixture='''# instance fields
.field public final mStorageLock:Ljava/lang/Object;
.method public getVotes(I)Landroid/util/SparseArray;
    .registers 5
    iget-object v0, p0, Lcom/android/server/display/mode/VotesStorage;->mStorageLock:Ljava/lang/Object;
    move-object p0, p1
    return-object p1
.end method
.method public untouched()V
    return-void
.end method
'''
out=patch(fixture)
assert out.count('RenderVoteOwner;->merge')==1 and '.registers 6' in out
assert out.split('.method public untouched()',1)[1]==fixture.split('.method public untouched()',1)[1]
for invalid in (out,fixture.replace('.registers 5','.registers 6'),fixture.replace('    move-object p0, p1','    move-object v3, p1')):
    try:patch(invalid)
    except AssertionError:pass
    else:raise AssertionError('unsupported or duplicate hook accepted')

files={
'android/util/SparseArray.java':'''package android.util;public class SparseArray<T> extends java.util.TreeMap<Integer,T> {
 public T get(int key){return super.get(key);}public void put(int key,T value){super.put(key,value);}}
''',
'com/android/server/display/mode/Vote.java':'''package com.android.server.display.mode;
public final class Vote {
 public static class Range {public final float min,max;Range(float a,float b){min=a;max=b;}}
 public static class Ranges {public final Range physical,render;Ranges(float a,float b,float c,float d){physical=new Range(a,b);render=new Range(c,d);}}
 public final int width,height;public final Ranges refreshRateRanges;public final boolean disableRefreshRateSwitching;public final float appRequestBaseModeRefreshRate;
 public Vote(int w,int h,float a,float b,float c,float d,boolean s,float base){width=w;height=h;refreshRateRanges=new Ranges(a,b,c,d);disableRefreshRateSwitching=s;appRequestBaseModeRefreshRate=base;}
 public static String priorityToString(int n){return n==0?"PRIORITY_DEFAULT_REFRESH_RATE":"OTHER";}
}
''',
'com/android/server/display/mode/VotesStorage.java':'''package com.android.server.display.mode;
public class VotesStorage {public static final int ZUI_CONTROL_RENDER_HOOK=1;
 public interface Listener {void onChanged();}public final Listener mListener;public int notifications;
 public VotesStorage(){mListener=()->notifications++;}}
''',
'com/zui/server/control/RenderVoteTest.java':'''package com.zui.server.control;
import android.util.SparseArray;import com.android.server.display.mode.*;
public class RenderVoteTest {
 static void need(boolean b){if(!b)throw new AssertionError();}
 static Vote vote(float low,float high){return new Vote(2560,1600,60,165,low,high,true,120);}
 public static void main(String[] args)throws Exception {
  VotesStorage store=new VotesStorage(),other=new VotesStorage();SparseArray<Vote> original=new SparseArray<>();
  Vote base=vote(0,165),auth=vote(120,120),thermal=vote(0,90),user=vote(0,144);
  original.put(0,base);original.put(8,auth);original.put(16,thermal);original.put(7,user);
  RenderVoteOwner.update(store,60);need(store.notifications==1);RenderVoteOwner.update(store,60);need(store.notifications==1);
  SparseArray<Vote> copy=new SparseArray<>();copy.putAll(original);RenderVoteOwner.merge(store,copy);
  Vote own=copy.get(0);need(own!=base&&own.refreshRateRanges.render.max==60&&own.refreshRateRanges.render.min==0);
  need(own.width==base.width&&own.height==base.height&&own.disableRefreshRateSwitching&&own.appRequestBaseModeRefreshRate==120);
  need(own.refreshRateRanges.physical.min==60&&own.refreshRateRanges.physical.max==165);
  need(original.get(0)==base&&copy.get(8)==auth&&copy.get(16)==thermal&&copy.get(7)==user);
  need(RenderVoteOwner.merge(other,original)==original&&original.get(0)==base);
  Vote replacement=vote(90,144);copy.put(0,replacement);RenderVoteOwner.merge(store,copy);need(copy.get(0)==replacement);
  RenderVoteOwner.update(store,null);need(store.notifications==2);RenderVoteOwner.update(store,null);need(store.notifications==2);
  copy.put(0,replacement);RenderVoteOwner.merge(store,copy);need(copy.get(0)==replacement&&copy.get(8)==auth);
  RenderVoteOwner.update(store,120);SparseArray<Vote> empty=new SparseArray<>();RenderVoteOwner.merge(store,empty);need(empty.get(0).refreshRateRanges.render.max==120);
  boolean rejected=false;try{RenderVoteOwner.update(new Object(),60);}catch(Exception expected){rejected=true;}need(rejected);
  need(RenderVoteOwner.error().isEmpty());System.out.println("RENDER_OWNERSHIP_COMPOSE_RELEASE_OEM_UPDATES_IDEMPOTENCE_AND_UNSUPPORTED=PASS");
 }
}
'''}
with tempfile.TemporaryDirectory(prefix='render-owner-') as tmp:
    paths=[]
    for name,text in files.items():
        path=Path(tmp)/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf8');paths.append(str(path))
    subprocess.run(['javac','-encoding','UTF-8','-d',tmp,*paths,str(ROOT/'framework_patch/src/services/com/zui/server/control/RenderVoteOwner.java')],check=True)
    subprocess.run(['java','-cp',tmp,'com.zui.server.control.RenderVoteTest'],check=True)
print('READ_COPY_SMALI_PATCH_GUARDS=PASS; DEVICE_ARBITRATION=NOT_CLAIMED')
