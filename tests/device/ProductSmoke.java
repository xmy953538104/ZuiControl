import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import com.android.uiautomator.core.*;
import android.os.SystemClock;
import org.json.*;
import java.io.*;

/** Real GUI inputs; Root reads observe authority. No storage or kernel repairs. */
public final class ProductSmoke extends UiAutomatorTestCase {
 void waitTrace(String path,String kind,String required,long after)throws Exception{
  long end=SystemClock.elapsedRealtime()+8000;
  do{
   try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(path+"/app-trace.log"),"UTF-8"))){
    String line;while((line=reader.readLine())!=null){int start=line.indexOf("OwnerRenderTrace: ");if(start<0)continue;
     JSONObject e=new JSONObject(line.substring(start+18));
     if(e.optString("kind").equals(kind)&&e.optLong("elapsed_ns")>=after&&e.optString("page").contains(required))return;
    }
   }
   SystemClock.sleep(20);
  }while(SystemClock.elapsedRealtime()<end);
  fail("Current operation trace missing: "+kind+" / "+required);
 }
 void quickChoice(String path,String control,String prefix,String id)throws Exception{
  process("cmd","statusbar","expand-notifications");
  UiObject button=new UiObject(new UiSelector().descriptionStartsWith(prefix));
  assertTrue("Real notification control missing: "+prefix,button.waitForExists(4000));assertTrue(button.isEnabled());
  long start=SystemClock.elapsedRealtimeNanos();button.click();event(id+"_INPUT_RETURNED",start);
  waitTrace(path,"QUICK_TERMINAL","control="+control+";",start);
  if(!control.equals("monitor")){
   long end=SystemClock.elapsedRealtime()+4000;boolean confirmed=false;
   do{if(button.exists()){String text=button.getContentDescription();if(text.contains("已选择")&&!text.contains("应用中")&&!text.contains("核对中")&&!text.contains("失败")){confirmed=true;break;}}SystemClock.sleep(20);}while(SystemClock.elapsedRealtime()<end);
   assertTrue("ACK did not reach current notification selection",confirmed);
  }
  event(id+"_GUI_TERMINAL_OBSERVED",start);
 }
 public void testAdviceQuickControls()throws Exception{
  Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(0).setActionAcknowledgmentTimeout(0);
  String path=getParams().getString("out");assertTrue(path.startsWith("/data/local/tmp/baseline-ui-"));log=new PrintWriter(path+"/events.jsonl");
  assertTrue(process("dumpsys","zui_control").contains("monitorRecordState=IDLE"));
  int originalMonitor=process("dumpsys","zui_control").contains("monitorMode=1\n")?1:0;
  JSONObject original=global();String apps=policy().getJSONArray("apps").toString();
  String mode=original.getString("uperfMode");int hz=original.getInt("refreshHz");
  java.util.Map<String,String> titles=new java.util.HashMap<>();titles.put("powersave","节能");titles.put("balance","均衡");titles.put("performance","性能");titles.put("fast","快速");
  assertTrue("Current rate cannot be restored through accepted GUI",java.util.Arrays.asList(60,90,120,144,165).contains(hz));
  try{
   getUiDevice().pressHome();
   int[] available={60,90,120,144,165};int offset=0;while(available[offset]!=hz)offset++;
   int[] rates=new int[5];for(int i=0;i<5;i++)rates[i]=available[(offset+i+1)%5];
   for(int i=0;i<rates.length;i++){quickChoice(path,"refresh",rates[i]+"Hz ","REFRESH_"+(i+1));waitGlobal("refreshHz",Integer.toString(rates[i]));}
   waitGlobal("refreshHz",Integer.toString(hz));
   String[] availableModes={"powersave","balance","performance","fast"};int modeOffset=java.util.Arrays.asList(availableModes).indexOf(mode);
   String[] modes=new String[5];for(int i=0;i<5;i++)modes[i]=availableModes[(modeOffset+i+1)%4];
   for(int i=0;i<modes.length;i++){quickChoice(path,"uperf",titles.get(modes[i])+" ","UPERF_"+(i+1));waitGlobal("uperfMode",modes[i]);}
   quickChoice(path,"uperf",titles.get(mode)+" ","UPERF_RESTORE");waitGlobal("uperfMode",mode);
   for(int i=0;i<5;i++){quickChoice(path,"monitor","监视器 ","MONITOR_"+(i+1));waitMonitor((originalMonitor+i+1)%2);}
   quickChoice(path,"monitor","监视器 ","MONITOR_RESTORE");waitMonitor(originalMonitor);
   assertEquals("HOME controls changed an App override",apps,policy().getJSONArray("apps").toString());
   assertEquals("Owner global semantics not restored",original.toString(),global().toString());
  }catch(Exception|AssertionError failure){
   log.println(new JSONObject().put("event","PRIMARY_QUICK_FAILURE").put("error",failure.toString()));log.flush();throw failure;
  }finally{
   try{
    getUiDevice().pressHome();
    if(global().getInt("refreshHz")!=hz){quickChoice(path,"refresh",hz+"Hz ","REFRESH_CLEANUP");waitGlobal("refreshHz",Integer.toString(hz));}
    if(!global().getString("uperfMode").equals(mode)){quickChoice(path,"uperf",titles.get(mode)+" ","UPERF_CLEANUP");waitGlobal("uperfMode",mode);}
    if(!process("dumpsys","zui_control").contains("monitorMode="+originalMonitor+"\n")){quickChoice(path,"monitor","监视器 ","MONITOR_CLEANUP");waitMonitor(originalMonitor);}
   }finally{
   process("cmd","statusbar","collapse");process("am","start","-n","com.zui.zuicontrol/.MainActivity");log.close();
   }
  }
 }
 public void testAdviceNavigation()throws Exception{
  Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(0).setActionAcknowledgmentTimeout(0);
  String path=getParams().getString("out");assertTrue(path.startsWith("/data/local/tmp/baseline-ui-"));log=new PrintWriter(path+"/events.jsonl");
  process("am","start","-n","com.zui.zuicontrol/.MainActivity");waitText("调控",5000);click("调控");
  String[] sections={"线程","监测","设置","调控"};
  try{for(int i=0;i<20;i++){
   long start=SystemClock.elapsedRealtimeNanos();click(sections[i%4]);event("NAV_"+(i+1)+"_INPUT_RETURNED",start);
   waitTrace(path,"PAGE_TRANSITION_END","children=1;y=0.0",start);event("NAV_"+(i+1)+"_READY",start);
  }}finally{log.close();}
 }
 public void testAdviceCallbackFallback()throws Exception{
  Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(0).setActionAcknowledgmentTimeout(0);
  String path=getParams().getString("out");assertTrue(path.startsWith("/data/local/tmp/baseline-ui-"));log=new PrintWriter(path+"/events.jsonl");
  JSONObject original=global();int hz=original.getInt("refreshHz"),target=hz==90?120:90;String apps=policy().getJSONArray("apps").toString();
  try{
   getUiDevice().pressHome();
   process("am","broadcast","-a","com.zui.zuicontrol.probe.OP","--es","op","controlsDisconnect");
   long start=SystemClock.elapsedRealtimeNanos();process("cmd","statusbar","expand-notifications");
   UiObject button=new UiObject(new UiSelector().descriptionStartsWith(target+"Hz "));assertTrue(button.waitForExists(4000));button.click();event("FALLBACK_REAL_GUI_CLICK",start);
   waitTrace(path,"QUICK_TERMINAL","control=refresh;",start);waitGlobal("refreshHz",Integer.toString(target));event("FALLBACK_AUTHORITY_CONFIRMED",start);
  }finally{
   process("am","broadcast","-a","com.zui.zuicontrol.probe.OP","--es","op","controlsReconnect");
   getUiDevice().pressHome();quickChoice(path,"refresh",hz+"Hz ","FALLBACK_RESTORE");waitGlobal("refreshHz",Integer.toString(hz));
   assertEquals(apps,policy().getJSONArray("apps").toString());assertEquals(original.toString(),global().toString());
   process("cmd","statusbar","collapse");process("am","start","-n","com.zui.zuicontrol/.MainActivity");log.close();
  }
 }
 void memoryStage(String path,String name,String visibleText)throws Exception{
  long start=SystemClock.elapsedRealtimeNanos();
  for(int i=0;i<2;i++){
   SystemClock.sleep(15000);
   if(visibleText.isEmpty())getUiDevice().pressHome();else click(visibleText);
  }
  event(name+"_STABLE_30S",start);
  getUiDevice().takeScreenshot(new File(path+"/"+name+".png"));
  log.println(new JSONObject().put("event",name+"_RESOURCE_CAPTURE").put("output",process("sh",path+"/memory-stage.sh",name)));
  log.flush();
 }
 void quickMonitor()throws Exception{
  process("cmd","statusbar","expand-notifications");
  UiObject button=new UiObject(new UiSelector().descriptionStartsWith("监视器 "));
  assertTrue("Real notification Monitor control missing",button.waitForExists(4000));button.click();
  process("cmd","statusbar","collapse");
 }
 void waitMonitor(int mode)throws Exception{
  long end=SystemClock.elapsedRealtime()+5000;
  while(SystemClock.elapsedRealtime()<end){if(process("dumpsys","zui_control").contains("monitorMode="+mode+"\n"))return;SystemClock.sleep(50);}
  fail("Actual Monitor mode not confirmed: "+mode);
 }
 public void testAdviceMemoryStates()throws Exception{
  Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(0).setActionAcknowledgmentTimeout(0);
  String path=getParams().getString("out");assertTrue(path.startsWith("/data/local/tmp/baseline-ui-"));
  String before=process("dumpsys","zui_control");assertTrue(before.contains("monitorRecordState=IDLE"));
  assertTrue("Preserve an already active Overlay",before.contains("monitorMode=0\n"));
  log=new PrintWriter(path+"/events.jsonl");
  try{
   process("am","start","-n","com.zui.zuicontrol/.MainActivity");waitText("调控",5000);
   assertFalse("Keep Owner draft",object("未保存").exists());click("调控");waitText("系统全局状态",5000);
   memoryStage(path,"HOME","系统全局状态");
   click("线程");waitText("全局调度默认",5000);assertFalse(generation().isEmpty());
   memoryStage(path,"THREAD","线程策略");
   getUiDevice().pressHome();waitLeftApp();memoryStage(path,"NOTIFICATION","");
   quickMonitor();waitMonitor(1);getUiDevice().pressHome();memoryStage(path,"OVERLAY","");
   quickMonitor();waitMonitor(0);getUiDevice().pressHome();
   log.println(new JSONObject().put("event","EXPLICIT_TRIM_REQUEST").put("result",process("am","send-trim-memory","com.zui.zuicontrol","RUNNING_LOW")));log.flush();
   memoryStage(path,"TRIM","");
  }finally{
   if(process("dumpsys","zui_control").contains("monitorMode=1\n")){quickMonitor();waitMonitor(0);}
   process("am","start","-n","com.zui.zuicontrol/.MainActivity");
   log.close();
  }
 }
 PrintWriter log;
 String process(String... args)throws Exception{Process p=new ProcessBuilder(args).redirectErrorStream(true).start();ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;while((n=p.getInputStream().read(buf))>0)b.write(buf,0,n);String text=b.toString("UTF-8").trim();assertEquals(args[0]+" failed: "+text.substring(0,Math.min(1000,text.length())),0,p.waitFor());return text;}
 JSONObject policy()throws Exception{return new JSONObject(process("cat","/data/system/zui_control/policy-v2.json"));}
 JSONObject row(JSONObject p,String key)throws Exception{JSONArray rows=p.getJSONArray("apps");for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("key").equals(key))return rows.getJSONObject(i).getJSONObject("value");throw new AssertionError("Dedicated existing policy row missing");}
 UiObject object(String text){return new UiObject(new UiSelector().text(text));}
 void waitText(String text,long ms)throws Exception{long end=SystemClock.elapsedRealtime()+ms;while(SystemClock.elapsedRealtime()<end){if(object(text).exists())return;SystemClock.sleep(20);}throw new AssertionError("Actual GUI missing: "+text);}
 void waitAbsent(String text,long ms)throws Exception{long end=SystemClock.elapsedRealtime()+ms;while(object(text).exists()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(20);assertFalse("Modal has not left the view tree",object(text).exists());}
 void waitLeftApp()throws Exception{long end=SystemClock.elapsedRealtime()+4000;while("com.zui.zuicontrol".equals(getUiDevice().getCurrentPackageName())&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(20);assertFalse("Old App window still active","com.zui.zuicontrol".equals(getUiDevice().getCurrentPackageName()));}
 Process launchApp()throws Exception{return new ProcessBuilder("am","start","-n","com.zui.zuicontrol/.MainActivity").redirectErrorStream(true).start();}
 void joinLaunch(Process p,String id,long start)throws Exception{if(!p.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)){p.destroyForcibly();fail("AM wrapper did not finish");}assertEquals(0,p.exitValue());p.getInputStream().close();event(id+"_AM_EXIT_OBSERVED",start);}
 void click(String text)throws Exception{waitText(text,4000);android.graphics.Rect r=object(text).getBounds();assertTrue(getUiDevice().click(r.centerX(),r.centerY()));}
 void clickRight(String text)throws Exception{for(int i=0;i<16;i++){UiObject o=new UiObject(new UiSelector().text(text).instance(i));if(!o.exists())break;android.graphics.Rect r=o.getBounds();if(r.centerX()>getUiDevice().getDisplayWidth()/3){assertTrue(getUiDevice().click(r.centerX(),r.centerY()));return;}}throw new AssertionError("Actual right-pane control missing: "+text);}
 void event(String name,long start)throws Exception{log.println(new JSONObject().put("event",name).put("elapsed_ms",(SystemClock.elapsedRealtimeNanos()-start)/1e6).put("monotonic_ns",SystemClock.elapsedRealtimeNanos()));log.flush();}
 long healthReads(String state){java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?m)^monitorHealthReads=([0-9]+)$").matcher(state);assertTrue("Missing production health counter",m.find());return Long.parseLong(m.group(1));}
 void savePolicy(long generation,String id)throws Exception{long start=SystemClock.elapsedRealtimeNanos();click("保存并生效");event(id+"_INPUT_RETURNED",start);long end=SystemClock.elapsedRealtime()+5000;while(object("未保存").exists()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(20);assertFalse("Draft not confirmed",object("未保存").exists());event(id+"_GUI_CONFIRMED",start);assertTrue(policy().getLong("generation")>generation);log.println(new JSONObject().put("event",id+"_AUTHORITY_AND_RUNTIME").put("health",process("dumpsys","zui_control")));log.flush();}
 String generation()throws Exception{UiObject label=new UiObject(new UiSelector().textContains("生效 generation "));if(!label.exists())return "";try{java.util.regex.Matcher m=java.util.regex.Pattern.compile("g[0-9a-f]{24}").matcher(label.getText());return m.find()?m.group():"";}catch(UiObjectNotFoundException replacedDuringQuery){return "";}}
 void waitQualifiedDraw(String path,long notBefore)throws Exception{
  long end=SystemClock.elapsedRealtime()+4000;
  do{try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(path+"/app-trace.log"),"UTF-8"))){String line;while((line=reader.readLine())!=null){int start=line.indexOf("ZuiResponse: ");if(start<0)continue;JSONObject e=new JSONObject(line.substring(start+13));if(e.getString("phase").equals("EDITABLE_DRAW")&&e.getLong("ns")>=notBefore)return;}}SystemClock.sleep(20);}while(SystemClock.elapsedRealtime()<end);
  fail("Current authority has not reached the editable draw; cached generation alone is insufficient");
 }
 void ruleSave(int mode)throws Exception{String old=generation();assertFalse(old.isEmpty());click("全局调度默认");waitText("全局模式",3000);click("mode"+mode);long start=SystemClock.elapsedRealtimeNanos();click("保存并应用");event("RULE_MODE"+mode+"_INPUT_RETURNED",start);long end=SystemClock.elapsedRealtime()+5000;while((generation().isEmpty()||generation().equals(old))&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(20);assertFalse("Current generation missing",generation().isEmpty());assertFalse("Save not confirmed",generation().equals(old));event("RULE_MODE"+mode+"_CURRENT_GUI_CONFIRMED",start);}
 JSONObject global()throws Exception{return policy().getJSONArray("globals").getJSONObject(0).getJSONObject("value");}
 void waitGlobal(String field,String expected)throws Exception{long end=SystemClock.elapsedRealtime()+5000;while(!global().getString(field).equals(expected)&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(25);assertEquals(expected,global().getString(field));}
 public void testReadResponse()throws Exception{
  Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(0).setActionAcknowledgmentTimeout(0);
  String path=getParams().getString("out");assertTrue(path.startsWith("/data/local/tmp/baseline-ui-"));
  assertFalse("Keep an active local draft",object("未保存").exists());
  assertTrue(process("dumpsys","zui_control").contains("monitorRecordState=IDLE"));
  String original=process("/system/bin/ZUIopt","--control","state","","");String trace=process("getprop","log.tag.ZuiResponse");
  log=new PrintWriter(new OutputStreamWriter(new FileOutputStream(path+"/events.jsonl"),"UTF-8"));
  try{process("setprop","log.tag.ZuiResponse","VERBOSE");
   for(int i=1;i<=3;i++){
    process("am","force-stop","com.zui.zuicontrol");waitLeftApp();long launch=SystemClock.elapsedRealtimeNanos();Process cold=launchApp();
    waitText("系统全局状态",5000);event("COLD_"+i+"_HOME_CONTENT_OBSERVED",launch);
    long nav=SystemClock.elapsedRealtimeNanos();click("线程");event("COLD_"+i+"_THREAD_INPUT_RETURNED",nav);
    long end=SystemClock.elapsedRealtime()+5000;while(generation().isEmpty()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(20);
    assertFalse("Current thread authority not ready",generation().isEmpty());event("COLD_"+i+"_THREAD_AUTHORITY_OBSERVED",nav);
    joinLaunch(cold,"COLD_"+i,launch);
    getUiDevice().pressHome();waitLeftApp();long warm=SystemClock.elapsedRealtimeNanos();Process resumed=launchApp();
    waitText("全局调度默认",5000);event("WARM_"+i+"_THREAD_CONTENT_OBSERVED",warm);
    end=SystemClock.elapsedRealtime()+5000;while(generation().isEmpty()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(20);
    assertFalse(generation().isEmpty());event("WARM_"+i+"_THREAD_AUTHORITY_OBSERVED",warm);
    joinLaunch(resumed,"WARM_"+i,warm);
   }
   assertEquals("Read/navigation changed rules",original,process("/system/bin/ZUIopt","--control","state","",""));
   log.println(new JSONObject().put("event","READ_ONLY_RULE_AUTHORITY_UNCHANGED").put("rule_writes",0));
  }finally{process("setprop","log.tag.ZuiResponse",trace);log.close();}
 }
 public void testGlobalsAndCloud()throws Exception{
  Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(0).setActionAcknowledgmentTimeout(0);String path=getParams().getString("out");assertTrue(path.startsWith("/data/local/tmp/baseline-ui-"));log=new PrintWriter(path+"/events.jsonl");long start=SystemClock.elapsedRealtimeNanos();
  try{click("调控");waitText("系统全局状态",3000);JSONObject original=global();assertEquals(120,original.getInt("refreshHz"));assertEquals("balance",original.getString("uperfMode"));
   clickRight("90Hz");waitGlobal("refreshHz","90");getUiDevice().pressHome();log.println(new JSONObject().put("event","GLOBAL_REFRESH_HOME_RUNTIME").put("health",process("dumpsys","zui_control")));process("am","start","-n","com.zui.zuicontrol/.MainActivity");waitText("系统全局状态",3000);clickRight("120Hz");waitGlobal("refreshHz","120");
   clickRight("节能");waitGlobal("uperfMode","powersave");getUiDevice().pressHome();log.println(new JSONObject().put("event","GLOBAL_UPERF_HOME_RUNTIME").put("health",process("dumpsys","zui_control")));process("am","start","-n","com.zui.zuicontrol/.MainActivity");waitText("系统全局状态",3000);clickRight("均衡");waitGlobal("uperfMode","balance");assertEquals(original.toString(),global().toString());event("GLOBAL_REFRESH_UPERF_SAVED_AND_RESTORED",start);
   click("线程");waitText("全局调度默认",3000);String old=generation();assertFalse(old.isEmpty());click("检查规则更新");long end=SystemClock.elapsedRealtime()+20000;UiObject latest=new UiObject(new UiSelector().textContains("已是最新"));while(!latest.exists()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(50);assertTrue("Real App manifest check incomplete",latest.exists());assertEquals(old,generation());event("REAL_APP_CLOUD_MANIFEST_NO_CHANGE",start);
  }finally{log.close();}
 }
 void checkPowerHealth(String path)throws Exception{
   click("调控");waitText("系统全局状态",3000);
   long before=healthReads(process("dumpsys","zui_control"));
   click("电池净功率");waitText("电池与供电",3000);String first=waitHealth();
   assertEquals(before+1,healthReads(process("dumpsys","zui_control")));
   assertTrue("Cycles must be integers",first.matches("(?s).*循环次数：[0-9]+ · 本次读取 .*"));
   getUiDevice().takeScreenshot(new File(path+"/power-health.png"));
   long tickStart=SystemClock.elapsedRealtimeNanos();SystemClock.sleep(12000);
   assertEquals(first,new UiObject(new UiSelector().textContains("FCC ")).getText());
   assertEquals(before+1,healthReads(process("dumpsys","zui_control")));event("POWER_TICKS_HEALTH_UNCHANGED",tickStart);
   getUiDevice().pressHome();waitLeftApp();process("am","start","-n","com.zui.zuicontrol/.MainActivity");waitText("电池与供电",3000);
   assertEquals(first,waitHealth());assertEquals(before+1,healthReads(process("dumpsys","zui_control")));
   click("关闭");waitAbsent("电池与供电",3000);SystemClock.sleep(6000);
   assertEquals(before+1,healthReads(process("dumpsys","zui_control")));
   click("电池净功率");waitText("电池与供电",3000);String second=waitHealth();
   assertFalse("Reopen must show a new read time",first.equals(second));
   assertEquals(before+2,healthReads(process("dumpsys","zui_control")));
   click("关闭");waitAbsent("电池与供电",3000);
   click("设置");waitText("监测与显示",3000);click("关于");assertFalse("Removed About entry",object("电池健康与容量").exists());
   click("调控");waitText("系统全局状态",3000);
   log.println(new JSONObject().put("event","HEALTH_EXPLICIT_OPEN_GUI_COUNTS").put("before",before).put("after",before+2).put("first",first).put("second",second));log.flush();
 }
 String waitHealth()throws Exception{long end=SystemClock.elapsedRealtime()+5000;UiObject text=new UiObject(new UiSelector().textContains("FCC "));while(!text.exists()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(20);assertTrue("Current health snapshot missing",text.exists());return text.getText();}
 public void testBatteryOnDemandAcrossIdle()throws Exception{
  Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(0).setActionAcknowledgmentTimeout(0);
  String path=getParams().getString("out");assertTrue(path.startsWith("/data/local/tmp/baseline-ui-"));log=new PrintWriter(new OutputStreamWriter(new FileOutputStream(path+"/events.jsonl"),"UTF-8"));
  try{process("am","start","-n","com.zui.zuicontrol/.MainActivity");click("调控");waitText("系统全局状态",3000);
   assertFalse(object("电池与供电").exists());long before=healthReads(process("dumpsys","zui_control")),start=SystemClock.elapsedRealtimeNanos();
   SystemClock.sleep(305100);long after=healthReads(process("dumpsys","zui_control"));assertEquals(before,after);
   event("UNOPENED_305_SECONDS_NO_HEALTH_READ",start);log.println(new JSONObject().put("before",before).put("after",after));log.flush();checkPowerHealth(path);
  }finally{log.close();}
 }
 public void testPowerAndHealthOnly()throws Exception{
  Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(0).setActionAcknowledgmentTimeout(0);
  String path=getParams().getString("out");assertTrue(path.startsWith("/data/local/tmp/baseline-ui-"));
  log=new PrintWriter(new OutputStreamWriter(new FileOutputStream(path+"/events.jsonl"),"UTF-8"));
  try{process("am","start","-n","com.zui.zuicontrol/.MainActivity");checkPowerHealth(path);}finally{log.close();}
 }
 public void testResponseAndPower()throws Exception{
  Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(0).setActionAcknowledgmentTimeout(0).setScrollAcknowledgmentTimeout(0);
  String path=getParams().getString("out"),expected=getParams().getString("expectedUserHash");assertTrue(path.startsWith("/data/local/tmp/baseline-ui-"));assertNotNull(expected);
  String before=process("/system/bin/ZUIopt","--control","state","","");assertTrue(before.contains("user_sha256="+expected));
  assertTrue("Only this captured mode0/rt0 baseline is authorized",process("cat",path+"/original.conf").contains("runtime_defaults 0 0\n"));
  assertTrue(process("dumpsys","zui_control").contains("monitorRecordState=IDLE"));assertFalse(object("未保存").exists());
  String trace=process("getprop","log.tag.ZuiResponse");log=new PrintWriter(new OutputStreamWriter(new FileOutputStream(path+"/events.jsonl"),"UTF-8"));
  try{process("setprop","log.tag.ZuiResponse","VERBOSE");
   long readyNotBefore=SystemClock.elapsedRealtimeNanos();process("am","start","-n","com.zui.zuicontrol/.MainActivity");click("线程");waitText("全局调度默认",5000);long end=SystemClock.elapsedRealtime()+5000;while(generation().isEmpty()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(20);assertFalse(generation().isEmpty());waitQualifiedDraw(path,readyNotBefore);
   for(int i=1;i<=3;i++){ruleSave(1);ruleSave(0);}
   String cancelled=generation();click("全局调度默认");waitText("全局模式",3000);click("mode1");click("取消");waitAbsent("全局模式",3000);assertEquals(cancelled,generation());event("GLOBAL_CANCEL_NO_WRITE",SystemClock.elapsedRealtimeNanos());
   assertTrue("Restored global save changed other Owner rules",process("/system/bin/ZUIopt","--control","state","","").contains("user_sha256="+expected));
   click("调控");waitText("系统全局状态",3000);JSONObject own=row(policy(),"0:com.zui.zuicontrol");assertEquals(120,own.getInt("refreshHz"));assertEquals("performance",own.getString("uperfMode"));assertEquals("DEFAULT_FOR_MODE",own.getString("gpuPolicy"));
   click("ZuiControl");waitText("自定义应用刷新率",3000);
   for(int i=1;i<=3;i++){
    long gen=policy().getLong("generation");clickRight("90Hz");clickRight("均衡");clickRight("自定义");savePolicy(gen,"APP_MODIFY_"+i);JSONObject modified=row(policy(),"0:com.zui.zuicontrol");assertEquals(90,modified.getInt("refreshHz"));assertEquals("balance",modified.getString("uperfMode"));assertEquals("CUSTOM",modified.getString("gpuPolicy"));
    gen=policy().getLong("generation");clickRight("120Hz");clickRight("性能");savePolicy(gen,"APP_RESTORE_"+i);assertEquals(own.toString(),row(policy(),"0:com.zui.zuicontrol").toString());
   }
   getUiDevice().pressHome();waitLeftApp();long warm=SystemClock.elapsedRealtimeNanos();Process p=launchApp();waitText("系统全局状态",5000);event("WARM_ACCEPTED_HOME_RESET",warm);joinLaunch(p,"WARM",warm);
   if(!"true".equals(getParams().getString("saveOnly","false")))checkPowerHealth(path);
  }finally{process("setprop","log.tag.ZuiResponse",trace);log.close();}
 }
 public void testPagesAndSave()throws Exception{
  Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(0).setActionAcknowledgmentTimeout(0).setScrollAcknowledgmentTimeout(0);
  String path=getParams().getString("out");assertTrue(path.startsWith("/data/local/tmp/baseline-ui-"));log=new PrintWriter(new OutputStreamWriter(new FileOutputStream(path+"/events.jsonl"),"UTF-8"));
  try{
   long start=SystemClock.elapsedRealtimeNanos();if(!"true".equals(getParams().getString("remaining","false"))){process("am","force-stop","com.zui.zuicontrol");process("am","start","-n","com.zui.zuicontrol/.MainActivity");waitText("系统全局状态",4000);click("线程");waitText("全局调度默认",4000);long end=SystemClock.elapsedRealtime()+4000;while(generation().isEmpty()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(20);assertFalse(generation().isEmpty());event("COLD_PROCESS_START_TO_THREAD_AUTHORITY",start);
   String nativeBefore=process("/system/bin/ZUIopt","--control","state","","");assertTrue(nativeBefore.contains("user_sha256="));
   ruleSave(1);ruleSave(0);
   click("全局调度默认");waitText("全局模式",3000);click("mode1");click("取消");waitAbsent("全局模式",3000);event("RULE_CANCEL_DRAFT_ONLY",SystemClock.elapsedRealtimeNanos());}
   click("调控");waitText("系统全局状态",3000);JSONObject before=policy();JSONObject own=row(before,"0:com.zui.zuicontrol");assertEquals("DEFAULT_FOR_MODE",own.getString("gpuPolicy"));assertEquals(120,own.getInt("refreshHz"));assertEquals("performance",own.getString("uperfMode"));
   click("ZuiControl");waitText("自定义应用刷新率",3000);clickRight("90Hz");clickRight("均衡");clickRight("自定义");savePolicy(before.getLong("generation"),"APP_REFRESH_UPERF_GPU");JSONObject modified=row(policy(),"0:com.zui.zuicontrol");assertEquals(90,modified.getInt("refreshHz"));assertEquals("balance",modified.getString("uperfMode"));assertEquals("CUSTOM",modified.getString("gpuPolicy"));
   long next=policy().getLong("generation");clickRight("120Hz");clickRight("性能");savePolicy(next,"APP_POLICY_RESTORE");JSONObject restored=row(policy(),"0:com.zui.zuicontrol");assertEquals(own.toString(),restored.toString());
   getUiDevice().pressHome();long warm=SystemClock.elapsedRealtimeNanos();process("am","start","-n","com.zui.zuicontrol/.MainActivity");waitText("系统全局状态",3000);event("WARM_RETURN_GUI",warm);
   click("监测");waitText("监测记录",3000);event("MONITOR_PAGE_VISIBLE",start);
   click("设置");waitText("监测与显示",3000);click("数据与维护");waitText("立即备份",3000);click("恢复出厂配置");waitText("恢复出厂配置？",3000);click("取消");event("FACTORY_RESET_CANCEL_NO_WRITE",start);
   click("监测与显示");waitText("界面主题",3000);assertTrue(object("通知").exists());event("NOTIFICATION_AND_THEME_PAGE",start);
  }finally{log.close();}
 }
}
