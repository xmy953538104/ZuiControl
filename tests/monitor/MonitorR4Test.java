package com.zui.server.control;
import java.nio.file.*;
public final class MonitorR4Test {
 static void check(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[] args)throws Exception{
  MonitorSession s=new MonitorSession();s.scene("game",0,true);
  check(!s.sampling()&&!s.arm(0));s.toggle(MonitorSession.FULL);check(s.sampling());
  check(s.arm(100));check(!s.canStart(2099));check(s.canStart(2100));s.started();
  check(!s.canStart(9999)&&s.recordingActive());
  s.scene("other",0,true);check(s.recordingState().equals("PAUSED"));
  s.scene("game",0,false);check(s.recording()&&!s.sampling());
  s.scene("game",0,true);check(s.recordingActive());s.stop();check(!s.recording());
  s.arm(10000);s.scene("game",1,true);check(!s.canStart(12000));
  s.toggle(MonitorSession.FPS);check(s.mode==MonitorSession.FPS);s.toggle(MonitorSession.FPS);check(!s.sampling());
  check(MonitorSources.batteryWatts(0,3,4000,-2000000)==8.0);
  check(MonitorSources.batteryWatts(1,3,4000,-2000000)==-1);
  check(MonitorSources.batteryWatts(0,5,4000,0)==-1);
  check(MonitorSources.batteryWatts(0,3,4000,Long.MIN_VALUE)==-1);
  check(MonitorSources.batteryWatts(0,3,4000,2000000)==8.0);
  check(MonitorSources.batteryWatts(0,3,4430,391000)==4.43*0.391);
  for(int status:new int[]{-1,1,2,4,5})check(MonitorSources.batteryWatts(0,status,4430,2000000)==-1);
  for(int plugged:new int[]{-1,1,2,4,8})check(MonitorSources.batteryWatts(plugged,3,4430,2000000)==-1);
  for(int voltage:new int[]{-1,0,4,4430000,Integer.MAX_VALUE})check(MonitorSources.batteryWatts(0,3,voltage,2000000)==-1);
  for(long current:new long[]{0,2,-2,Integer.MIN_VALUE,Long.MIN_VALUE,Long.MAX_VALUE,2000000000})
   check(MonitorSources.batteryWatts(0,3,4430,current)==-1);
  Path root=Files.createTempDirectory("quiet-test-");
  try{
   try{MonitorSources.discoverQuiet(root.toFile());throw new AssertionError();}catch(java.io.IOException expected){
    check(expected.getMessage().equals("quiet_sensor_missing"));}
   Path one=Files.createDirectory(root.resolve("thermal_zone7"));
   Files.write(one.resolve("type"),"quiet-therm\n".getBytes("UTF-8"));
   check(MonitorSources.discoverQuiet(root.toFile()).equals(one.resolve("temp").toString()));
   Path two=Files.createDirectory(root.resolve("thermal_zone88"));
   Files.write(two.resolve("type"),"quiet-therm\n".getBytes("UTF-8"));
   try{MonitorSources.discoverQuiet(root.toFile());throw new AssertionError();}catch(java.io.IOException expected){
    check(expected.getMessage().equals("quiet_sensor_ambiguous"));}
   Files.delete(two.resolve("type"));Files.delete(two);Files.delete(one.resolve("type"));Files.delete(one);
  }finally{Files.delete(root);}
  System.out.println("R4_SESSION_HOLD_PAUSE_MANUAL_STOP_POWER_UNITS_QUIET_TYPE=PASS");
 }
}
