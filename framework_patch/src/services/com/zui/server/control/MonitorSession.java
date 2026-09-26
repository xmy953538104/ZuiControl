package com.zui.server.control;

/** Boot-local desired state. Recording is a bounded, non-resumable foreground lease. */
final class MonitorSession {
    static final int OFF=0, FULL=1, FPS=2;
    static final long MAX_RECORD_MS=1800000;
    int mode, user, recordingUser, recordingPid, taskId=-1;
    String foreground="", recordingPackage="";
    boolean eligible, recordEligible, circle, overlayAllowed=true;
    long targetEpoch, connectionEpoch, recordingStart, processStart;
    void scene(String pkg,int userId,boolean valid){scene(pkg,userId,valid,valid);}
    void scene(String pkg,int userId,boolean visible,boolean canRecord){
        if(!pkg.equals(foreground)||user!=userId)targetEpoch++;
        foreground=pkg;user=userId;eligible=visible;recordEligible=canRecord;
    }
    void toggle(int requested){mode=mode==requested?OFF:requested;circle=false;}
    boolean recording(){return !recordingPackage.isEmpty();}
    boolean sampling(){return eligible;}
    boolean visible(){return eligible&&overlayAllowed&&mode!=OFF;}
    long interval(){return (mode==OFF||!overlayAllowed)&&!recording()?5000:1000;}
    boolean canStart(){return mode==FULL&&overlayAllowed&&circle&&eligible&&recordEligible&&!recording();}
    void started(long now,int pid,long generation){
        recordingPackage=foreground;recordingUser=user;recordingStart=now;
        recordingPid=pid;processStart=generation;
    }
    String terminal(long now){
        if(!recording())return "";
        if(now-recordingStart>=MAX_RECORD_MS)return "DURATION_LIMIT";
        if(!eligible)return "SCREEN_OR_LOCK";
        if(user!=recordingUser||!foreground.equals(recordingPackage))return "FOREGROUND_CHANGED";
        if(!recordEligible)return "APP_BACKGROUND";
        return "";
    }
    boolean sameProcess(int pid,long generation){return recordingPid==pid&&processStart==generation;}
    void stop(){recordingPackage="";}
    String recordingState(){return recording()?"RECORDING":"IDLE";}
}
