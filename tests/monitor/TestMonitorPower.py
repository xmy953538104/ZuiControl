"""Real sysfs reader against bounded temporary files: units, missing data and TTL."""
from pathlib import Path
import subprocess,tempfile
ROOT=Path(__file__).resolve().parents[2]
SRC=ROOT/'framework_patch/src/services/com/zui/server/control'
test='''package com.zui.server.control;
import java.nio.file.*;import java.util.*;
public class MonitorPowerTest {
 static void need(boolean v){if(!v)throw new AssertionError();}
 static void put(Path p,String name,String value)throws Exception{Files.write(p.resolve(name),value.getBytes("UTF-8"));}
 public static void main(String[] args)throws Exception{
  Path root=Files.createTempDirectory("monitor-power-");Path battery=Files.createDirectory(root.resolve("battery"));
  try{
   put(battery,"charge_full","6000000");put(battery,"charge_full_design","6500000");
   put(battery,"cycle_count","42");put(battery,"soh","95");
   MonitorPower reader=new MonitorPower(root.toFile()){
    String read(String supply,String field){
     if(supply.startsWith("ucsi-source-psy-"))supply=supply.endsWith("ucsi1")?"port1":"port2";
     return super.read(supply,field);
    }
   };Map<String,Object> first=reader.health(100);
   need(Math.abs((Double)first.get("fccDesignPercent")-92.30769231)<0.00001);
   need(first.get("driverSohPercent").equals(95L));need(first.get("capacityScaling").equals(1));
   put(battery,"charge_full","3200000");need(reader.health(101)==first&&reader.healthReads==1);
   Map<String,Object> next=reader.health(300100);need(next!=first&&next.get("chargeFullUah").equals(3200000L));
   need((Double)next.get("fccDesignPercent")<50);need(!((Boolean)next.get("physicalCellCountQualified")));
   put(battery,"charge_full_design","3200000");need((Double)reader.health(600100).get("fccDesignPercent")==100);
   Files.delete(battery.resolve("charge_full"));need(reader.health(900100).get("fccDesignPercent")==null);
   put(battery,"charge_full","bad");need(reader.health(1200100).get("chargeFullUah")==null);
   need(MonitorPower.ratio(Long.MAX_VALUE,1L)==null&&MonitorPower.ratio(0L,6550000L)==null);
   need(MonitorPower.ratio(6600000L,6550000L)>100); // do not silently clamp driver over-design estimates
   need(reader.health(1)!=next);need(reader.healthReads==6); // elapsed-clock reset invalidates TTL
   need(MonitorPower.netWatts(0,3,4000,1000000)==-4);
   need(MonitorPower.netWatts(1,2,4000,-1000000)==4);
   need(MonitorPower.netWatts(1,5,4000,0)==0);
   need(MonitorPower.netWatts(0,3,4000,-1000000)==null);
   need(MonitorPower.netWatts(1,2,4000,1000000)==null);
   need(MonitorPower.netWatts(0,3,4000000,1000000)==null);
   need(MonitorPower.netWatts(0,3,4000,Long.MIN_VALUE)==null);
   reader.sample(1,5,4000,0,10);reader.edge(false);
   need(reader.sample(1,5,4000,0,11).get("batteryNetPowerW")==null);
   need(reader.sample(0,3,4000,0,12).get("batteryNetValidity").equals("WAITING_SOURCE_UPDATE"));
   need(reader.sample(0,3,4000,1000000,13).get("batteryNetPowerW").equals(-4.0));
   need(!reader.pending());
   Path one=Files.createDirectory(root.resolve("port1"));
   Path two=Files.createDirectory(root.resolve("port2"));
   Path usb=Files.createDirectory(root.resolve("usb"));Path other=Files.createDirectory(root.resolve("wireless"));
   put(one,"online","1");put(two,"online","0");put(usb,"voltage_now","5000000");put(usb,"current_now","200000");put(usb,"real_type","SDP");
   need(reader.sample(1,5,4000,0,14).get("inputPowerW").equals(1.0));
   put(one,"online","0");put(two,"online","1");put(other,"voltage_now","9000000");put(other,"current_now","300000");
   need(reader.sample(1,5,4000,0,15).get("inputPowerSupply").equals("wireless"));
   need(Math.abs((Double)reader.sample(1,5,4000,0,15).get("inputPowerW")-2.7)<1e-8);
   put(usb,"real_type","PD_PPS");need(reader.sample(1,5,4000,0,16).get("inputPowerW")==null);
   put(usb,"real_type","SDP");put(other,"voltage_now","-13000");need(reader.sample(1,5,4000,0,17).get("inputPowerW")==null);
   put(one,"online","1");need(reader.sample(1,5,4000,0,18).get("inputPowerW")==null);
   for(Path dir:new Path[]{one,two,usb,other}){for(Path p:Files.newDirectoryStream(dir))Files.delete(p);Files.delete(dir);}
   System.out.println("MONITOR_HEALTH_UNITS_RAW_SOURCE_NULL_AND_TTL=PASS");
   System.out.println("POWER_SIGN_ZERO_EDGE_PORT_SELECTION_PROTOCOL_AND_NULL=PASS");
  }finally{for(Path p:Files.newDirectoryStream(battery))Files.delete(p);Files.delete(battery);Files.delete(root);}
 }
}'''
with tempfile.TemporaryDirectory(prefix='zui-power-host-') as tmp:
 path=Path(tmp)/'MonitorPowerTest.java';path.write_text(test,encoding='utf8')
 subprocess.run(['javac','-encoding','UTF-8','-d',tmp,str(SRC/'MonitorPower.java'),str(SRC/'MonitorSources.java'),str(SRC/'MonitorSnapshot.java'),str(SRC/'PolicyJson.java'),str(path)],check=True)
 subprocess.run(['java','-cp',tmp,'com.zui.server.control.MonitorPowerTest'],check=True)
