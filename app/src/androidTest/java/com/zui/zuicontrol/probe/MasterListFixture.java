package com.zui.zuicontrol.probe;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.*;
import android.widget.*;
import android.text.*;
import android.util.Log;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.util.*;
import kotlin.Unit;
import com.zui.zuicontrol.OwnerUi;
import com.zui.zuicontrol.OwnerMasterList;

/** Isolated UI data; never changes session, policies, rules, records or backend authority. */
final class MasterListFixture {
 private final Activity activity;
 private final FrameLayout host,mask;
 private final OwnerMasterList viewport;
 private final ListView list;
 private final List<OwnerMasterList.Row> all=new ArrayList<>();
 private String selected="";
 private static Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
 MasterListFixture(Activity activity,int size)throws Exception{
  if(size!=3&&size!=500)throw new IllegalArgumentException("finite fixture size");this.activity=activity;
  host=(FrameLayout)field(activity,"ownerHost");OwnerUi ui=(OwnerUi)field(activity,"owner");
  mask=new FrameLayout(activity);mask.setBackgroundColor(ui.getMaster());
  LinearLayout column=new LinearLayout(activity);column.setOrientation(LinearLayout.VERTICAL);
  TextView title=ui.label("R6 隔离列表 "+size,16f,ui.getText(),800);column.addView(title,new LinearLayout.LayoutParams(-1,ui.px(32)));
  kotlin.Pair<View,EditText> search=ui.search("搜索隔离列表","");search.getSecond().setContentDescription("R6隔离搜索");column.addView(search.getFirst(),new LinearLayout.LayoutParams(-1,ui.px(38)));
  viewport=new OwnerMasterList(activity,ui,key->new ColorDrawable(0xff6688aa),key->{selected=key;viewportSelect(key);return Unit.INSTANCE;});viewport.showSection("fixture");list=(ListView)field(viewport,"list");list.setContentDescription("R6隔离主列表");
  for(int i=0;i<size;i++)all.add(new OwnerMasterList.Row(String.format(Locale.ROOT,"review.fixture.%04d",i),String.format(Locale.ROOT,"合成App-%04d",i),"",Arrays.asList(new OwnerMasterList.Chip("120Hz",0,false),new OwnerMasterList.Chip("均衡",2,false)),i%3==0));
  viewport.submit(all,"无匹配");column.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));
  Button close=new Button(activity);close.setText("结束隔离测量");close.setOnClickListener(v->close());column.addView(close,new LinearLayout.LayoutParams(-1,ui.px(42)));mask.addView(column,new FrameLayout.LayoutParams(-1,-1));
  View master=(View)field(activity,"master");host.addView(mask,new FrameLayout.LayoutParams(master.getWidth(),host.getHeight(),Gravity.TOP|Gravity.LEFT));
  search.getSecond().addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){viewport.submit(OwnerMasterList.Companion.matching(all,s.toString()),"无匹配");viewport.select(selected);}public void afterTextChanged(Editable e){}});
 }
 private void viewportSelect(String key){viewport.select(key);try{snapshot("SELECTED");}catch(Exception e){throw new RuntimeException(e);}}
 void snapshot(String phase)throws Exception{
  Log.i("R6Probe",new JSONObject().put("event","ISOLATED_LIST").put("phase",phase).put("boottime_ns",android.os.SystemClock.elapsedRealtimeNanos()).put("declared_rows",all.size()).put("matching_rows",list.getAdapter().getCount()).put("visible_rows",list.getChildCount()).put("created_rows",field(viewport,"createdRowCount")).put("binds",field(viewport,"bindCount")).put("first",list.getFirstVisiblePosition()).put("selected",selected).toString());
 }
 void end(){list.setSelection(list.getAdapter().getCount()-1);list.post(()->{try{snapshot("END");}catch(Exception e){throw new RuntimeException(e);}});}
 void close(){host.removeView(mask);}
}
