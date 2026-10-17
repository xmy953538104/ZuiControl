"""Actual collector regression: one edge owns one bounded confirmation deadline."""
from pathlib import Path
import subprocess,tempfile

source=Path(__file__).with_name('TestPowerEdge.py');namespace={'__file__':str(source)}
text=source.read_text(encoding='utf8')
exec(compile(text[:text.index('\nwith tempfile.TemporaryDirectory(')],str(source),'exec'),namespace)
stubs=dict(namespace['stubs'])
stubs['com/zui/server/control/DeadlineTest.java']='''package com.zui.server.control;
import android.content.*;import android.os.*;import java.util.*;
public class DeadlineTest {
 static MonitorCollector c;static PowerEdgeTest.Client client;
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static long metric(String key){return PowerEdgeTest.metric(c,key);}
 static void begin(boolean full)throws Exception {client=new PowerEdgeTest.Client();c=PowerEdgeTest.begin(full,client);}
 static void finish(){check(Handler.pending.size()==1,"one scalar clock");check(metric("monitorThreadEnumerations")==0&&metric("monitorDbWrites")==0,"no scan/DB");c.unregister(client);check(HandlerThread.active==0,"retired");}
 static long edge()throws Exception {
  Intent.plugged=1;Intent.status=2;BatteryManager.currentUa=-2000000;
  c.invalidatePower(false);Handler.next();check(Handler.delays.get(0)==800,"initial confirmation");
  return Handler.due.get(0);
 }
 static void fact(long now,long ua)throws Exception {
  SystemClock.now=now;Intent.plugged=0;Intent.status=3;BatteryManager.currentUa=ua;
  c.onBatteryStateChanged(new Intent());if(Handler.delays.get(0)==0)Handler.next();
 }
 static void pending(boolean full)throws Exception {
  begin(full);long planned=edge();
  fact(planned-600,-1999000);
  check(Handler.due.get(0)==planned,"original failing case: pending event must retain deadline");
  check(metric("monitorPowerRechecksPlanned")==1&&metric("monitorPowerRechecksExecuted")==0,"not consumed by schedule/event");
  fact(planned-1,-1998000);check(Handler.due.get(0)==planned,"near-deadline event does not extend");
  Handler.next();check(metric("monitorPowerRechecksExecuted")==1,"one actual confirmation");
  check(Handler.delays.get(0)==(full?1000:5000),"no repeating recheck after pending confirmation");
  for(int i=0;i<3;i++)Handler.next();
  check(metric("monitorPowerRechecksPlanned")==1&&metric("monitorPowerRechecksExecuted")==1,"budget not renewed by cadence");
  long next=Handler.due.get(0);c.invalidatePower(false);
  check(Handler.due.get(0)==next&&metric("monitorPowerRechecksPlanned")==1,"duplicate action cannot renew an exhausted budget");
  finish();System.out.println("PENDING="+(full?"FULL":"OFF")+" planned="+planned+" executed="+planned+" count=1");
 }
 static void early()throws Exception {
  begin(false);long planned=edge();fact(planned-600,2000000);
  check(metric("monitorPowerRechecksMerged")==1&&metric("monitorPowerRechecksExecuted")==0,"early valid source merges unused confirmation");
  check(metric("monitorPowerRecheckDeadlineMs")==0&&Handler.delays.get(0)==5000,"normal cadence after confirmed source");
  long before=metric("monitorSamples");c.onBatteryStateChanged(new Intent());check(metric("monitorSamples")==before&&Handler.delays.get(0)==5000,"same value no fake event");
  finish();
 }
 static UEventObserver observer()throws Exception {java.lang.reflect.Field f=MonitorCollector.class.getDeclaredField("powerEvents");f.setAccessible(true);return (UEventObserver)f.get(c);}
 static void zero()throws Exception {
  begin(false);Intent.plugged=1;Intent.status=5;BatteryManager.currentUa=0;c.onBatteryStateChanged(new Intent());Handler.next();c.invalidatePower(true);Handler.next();long planned=Handler.due.get(0);
  c.invalidatePower(true);check(Handler.due.get(0)==planned&&metric("monitorPowerRechecksPlanned")==1,"duplicate broadcast keeps same deadline");
  Map<String,String> fields=new HashMap<>();fields.put("POWER_SUPPLY_NAME","battery");fields.put("POWER_SUPPLY_CURRENT_NOW","0");
  observer().onUEvent(new UEventObserver.UEvent(fields));Handler.next();Handler.next();
  check(((Number)PowerEdgeTest.snapshot(c).get("batteryNetPowerW")).doubleValue()==0,"notified real zero accepted by unchanged source contract");
  check(metric("monitorPowerRechecksExecuted")==0&&metric("monitorPowerRechecksMerged")==1,"zero confirmation merged once");
  check(PowerEdgeTest.snapshot(c).get("batteryNetSourceMeasurementTime")==null,"no ADC timestamp invented");finish();
 }
 static void modesAndSleep()throws Exception {
  begin(false);long planned=edge();SystemClock.now=planned-600;
  c.command("full",0,"");Handler.next();check(Handler.due.get(0)==planned,"OFF to FULL keeps edge deadline");
  c.command("off",0,"");Handler.next();check(Handler.due.get(0)==planned,"FULL to OFF keeps edge deadline");
  c.scene("org.example.app",0,false);check(HandlerThread.active==0,"normal screen policy");
  SystemClock.now=planned+50;c.scene("org.example.app",0,true);Handler.next();
  check(metric("monitorPowerRechecksExecuted")==1&&Handler.delays.get(0)==5000,"overdue wake executes once immediately");finish();
 }
 static void oppositeAndLate()throws Exception {
  begin(false);long old=edge();Runnable stale=Handler.pending.get(0);
  SystemClock.now=old-500;c.invalidatePower(true);Handler.next();long next=Handler.due.get(0);check(next==SystemClock.now+800,"new reverse edge gets new deadline");
  long count=metric("monitorSamples");stale.run();check(metric("monitorSamples")==count&&Handler.due.get(0)==next,"old clock cannot consume new edge");
  Intent.plugged=0;Intent.status=3;BatteryManager.currentUa=-1999000;c.onBatteryStateChanged(new Intent());Handler.next();
  check(Handler.due.get(0)==next,"old-state fact cannot confirm new connected edge");
  Handler.next();check(metric("monitorPowerRechecksExecuted")==1,"only current edge executes");finish();
 }
 static void queuedEventSurvivesMode()throws Exception {
  begin(false);Intent.plugged=1;Intent.status=5;BatteryManager.currentUa=0;c.onBatteryStateChanged(new Intent());Handler.next();c.invalidatePower(true);Handler.next();
  Map<String,String> fields=new HashMap<>();fields.put("POWER_SUPPLY_NAME","battery");fields.put("POWER_SUPPLY_CURRENT_NOW","0");
  observer().onUEvent(new UEventObserver.UEvent(fields));c.command("full",0,"");
  while(Handler.delays.get(0)==0)Handler.next();
  check(((Number)PowerEdgeTest.snapshot(c).get("batteryNetPowerW")).doubleValue()==0,"mode change must not delete queued measurement callback");
  check(metric("monitorPowerRechecksExecuted")==0&&metric("monitorPowerRechecksMerged")==1,"queued confirmation budget merged");finish();
 }
 static void sourceDuplicates()throws Exception {
  begin(false);Map<String,String> fields=new HashMap<>();fields.put("POWER_SUPPLY_NAME","usb");fields.put("POWER_SUPPLY_ONLINE","1");fields.put("POWER_SUPPLY_REAL_TYPE","SDP");
  UEventObserver observer=observer();observer.onUEvent(new UEventObserver.UEvent(fields));Handler.next();Handler.next();long planned=Handler.due.get(0);
  for(int i=0;i<3;i++){observer.onUEvent(new UEventObserver.UEvent(fields));Handler.next();check(Handler.due.get(0)==planned,"same source/protocol retains deadline");}
  c.invalidatePower(true);check(Handler.due.get(0)==planned&&metric("monitorPowerEdge")==1,"matching Android action coalesces with hardware edge");
  Handler.next();check(metric("monitorPowerRechecksExecuted")==1&&metric("monitorPowerRechecksPlanned")==1,"source duplicates consume one budget");
  c.invalidatePower(false);Handler.next();long next=Handler.due.get(0);
  fields.put("POWER_SUPPLY_ONLINE","0");fields.put("POWER_SUPPLY_PRESENT","0");fields.put("POWER_SUPPLY_REAL_TYPE","Unknown");observer.onUEvent(new UEventObserver.UEvent(fields));Handler.next();
  check(metric("monitorPowerEdge")==2&&Handler.due.get(0)==next,"known online/present/protocol packet confirms the existing action edge");finish();
 }
 static void actionBeforeSource()throws Exception {
  begin(false);long planned=edge();SystemClock.now=planned-500;
  Map<String,String> fields=new HashMap<>();fields.put("POWER_SUPPLY_NAME","usb");fields.put("POWER_SUPPLY_ONLINE","0");fields.put("POWER_SUPPLY_REAL_TYPE","SDP");
  UEventObserver observer=observer();observer.onUEvent(new UEventObserver.UEvent(fields));Handler.next();
  check(metric("monitorPowerEdge")==1&&metric("monitorPowerRechecksPlanned")==1&&Handler.due.get(0)==planned,"Android action then matching first kernel fact cannot create a second edge");
  fields.put("POWER_SUPPLY_NAME","ucsi-source-psy-soc:qcom,pmic_glink:qcom,ucsi1");observer.onUEvent(new UEventObserver.UEvent(fields));Handler.next();
  check(metric("monitorPowerEdge")==1&&Handler.due.get(0)==planned,"first port baseline is not another transition");
  fields.put("POWER_SUPPLY_NAME","usb");fields.put("POWER_SUPPLY_REAL_TYPE","PD_PPS");observer.onUEvent(new UEventObserver.UEvent(fields));Handler.next();Handler.next();
  check(metric("monitorPowerEdge")==2&&metric("monitorPowerRechecksPlanned")==2&&Handler.due.get(0)==SystemClock.now+800,"actual known protocol change creates one new edge");finish();
 }
 public static void main(String[] args)throws Exception {
  pending(false);pending(true);early();zero();modesAndSleep();oppositeAndLate();queuedEventSurvivesMode();sourceDuplicates();actionBeforeSource();
  System.out.println("POWER_RECHECK_DEADLINE=PASS pending/early-valid/real-zero/duplicates/deadline-burst/reverse/stale-clock/modes/screen/queued-callback/bounded-no-data; PHYSICAL=NOT_CLAIMED");
 }
}'''
with tempfile.TemporaryDirectory(prefix='monitor-recheck-deadline-') as tmp:
    files=[]
    for name,text in stubs.items():
        p=Path(tmp)/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf8');files.append(str(p))
    production=['MonitorCollector.java','MonitorSources.java','MonitorPower.java','MonitorSnapshot.java','MonitorSession.java','MonitorLifecycle.java','ThreadAnalysis.java','PolicyJson.java']
    subprocess.run(['javac','-encoding','UTF-8','-d',tmp,*files,*[str(namespace['namespace']['src']/n) for n in production]],check=True)
    subprocess.run(['java','-cp',tmp,'com.zui.server.control.DeadlineTest'],check=True)

# Existing frozen-source checks strip only this explicit layer before checking old contracts.
import sys
sys.path.insert(0,str(source.parents[1]))
from BackendContract import POWER_RECHECK,reverse_response_battery
for row in POWER_RECHECK['files']:
    current=(source.parents[2]/row['path']).read_text(encoding='utf8')
    reverse_response_battery(row['path'],current)
    try:reverse_response_battery(row['path'],current+'\n// outside authorized deadline fix\n')
    except AssertionError:pass
    else:raise AssertionError('Out-of-scope mutation accepted: '+row['path'])
print('POWER_RECHECK_EXACT_SCOPE=PASS Collector plus source-event classification; old manifests unchanged')
