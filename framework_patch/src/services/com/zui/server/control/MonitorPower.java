package com.zui.server.control;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import static com.zui.server.control.PolicyJson.*;

/** TB321FU logical battery supply. Read-only; owned by the existing Monitor collector. */
final class MonitorPower {
    static final long HEALTH_TTL_MS = 300000;
    private final File supplies;
    private Map<String,Object> health;
    private long healthReadAt = -1;
    long scalarReads, healthReads;
    private long lastCurrent = Long.MIN_VALUE, edgeCurrent = Long.MIN_VALUE;
    private boolean awaitingCurrent;
    private int expectedConnected = -1;
    private final java.util.Map<String,Long> inputReadings=new java.util.HashMap<>(),edgeInputs=new java.util.HashMap<>();
    private boolean awaitingInput;
    private long notifiedCurrent = Long.MIN_VALUE;
    private final java.util.Map<String,Long> notifiedInputs=new java.util.HashMap<>();
    private final java.util.Map<String,String[]> sourceFacts=new java.util.HashMap<>();

    synchronized boolean sourceEvent(String name,String... fields) {
        if(!java.util.Arrays.asList("usb","wireless","ucsi-source-psy-soc:qcom,pmic_glink:qcom,ucsi1","ucsi-source-psy-soc:qcom,pmic_glink:qcom,ucsi2").contains(name) || fields.length!=4)return false;
        String[] old=sourceFacts.get(name),next=old==null?new String[4]:old.clone();
        boolean supplied=false;
        for(int i=0;i<fields.length;i++)if(fields[i]!=null && fields[i].length()<=96 && fields[i].indexOf('\n')<0 && fields[i].indexOf('\0')<0){next[i]=fields[i];supplied=true;}
        if(!supplied || java.util.Arrays.equals(old,next))return false;
        sourceFacts.put(name,next);return true;
    }
    synchronized void resetSourceEvents(){sourceFacts.clear();notifiedCurrent=Long.MIN_VALUE;notifiedInputs.clear();}
    boolean measurementEvent(String name,String current) {
        if(current==null || current.length()>32)return false;
        final long value;
        try { value=Long.parseLong(current); } catch(NumberFormatException e){return false;}
        if("battery".equals(name) && awaitingCurrent && value>=-30000000 && value<=30000000){
            notifiedCurrent=value;return true;
        }
        if(("usb".equals(name)||"wireless".equals(name)) && awaitingInput && value>=0 && value<=10000000){
            notifiedInputs.put(name,value);return true;
        }
        return false;
    }
    private void beginSourceUpdate(){
        edgeCurrent=lastCurrent;awaitingCurrent=true;edgeInputs.clear();edgeInputs.putAll(inputReadings);awaitingInput=true;
        notifiedCurrent=Long.MIN_VALUE;notifiedInputs.clear();
    }
    void sourceChanged() {
        String[] usb=sourceFacts.get("usb");
        expectedConnected=usb==null?-1:"1".equals(usb[0])?1:"0".equals(usb[0])?0:-1;
        beginSourceUpdate();
    }

    void edge(boolean connected) {
        expectedConnected=connected?1:0;
        beginSourceUpdate();
    }
    boolean pending(){return expectedConnected>=0 || awaitingCurrent;}
    boolean changedCurrent(long ua){return pending() && ua!=Long.MIN_VALUE && ua!=lastCurrent;}

    static Double netWatts(int plugged,int status,int mv,long ua) {
        if(plugged<0 || status<2 || status>5 || mv<2000 || mv>6000 || ua==Long.MIN_VALUE
                || ua < -30000000 || ua > 30000000 || (plugged==0 && (status!=3 || ua<=0))
                || (status==2 && ua>0))return null;
        // This logical supply exposes uA positive on discharge; net charging power is positive.
        return -ua*(mv/1000000000.0);
    }

    Map<String,Object> sample(int plugged,int status,int mv,long ua,long now) {
        boolean connectionMatches=expectedConnected<0 || (plugged>0?1:plugged)==expectedConnected;
        // A post-edge driver notification can confirm an unchanged read, never an ADC timestamp.
        boolean notified=ua!=Long.MIN_VALUE && ua==notifiedCurrent;
        if(awaitingCurrent && connectionMatches && netWatts(plugged,status,mv,ua)!=null
                && (notified || ua!=edgeCurrent && (edgeCurrent!=Long.MIN_VALUE || ua!=0)))awaitingCurrent=false;
        if(ua!=Long.MIN_VALUE)lastCurrent=ua;
        Double net=connectionMatches&&!awaitingCurrent?netWatts(plugged,status,mv,ua):null;
        String batteryValidity=!connectionMatches?"WAITING_POWER_STATE":awaitingCurrent?"WAITING_SOURCE_UPDATE":net==null?"UNAVAILABLE_SOURCE_OR_DIRECTION":"DRIVER_READING";
        String inputValidity=plugged==0?"DISCONNECTED":"UNAVAILABLE_PORT_MAPPING",supply="",protocol=null;
        if(plugged==0 && connectionMatches)awaitingInput=false;
        Long uv=null, inputUa=null;
        Double input=null;
        if(plugged>0 && connectionMatches) {
            Long one=number("usb","voltage_now"),two=number("wireless","voltage_now");
            boolean first=voltageValid(one),second=voltageValid(two);
            if(first!=second) {
                // The inactive path retains current readings; only one energized voltage path qualifies.
                supply=first?"usb":"wireless";uv=first?one:two;
                protocol=read("usb","real_type");
                boolean conventional=protocol!=null && java.util.Arrays.asList("SDP","CDP","DCP").contains(protocol);
                if(conventional)inputUa=number(supply,"current_now");
                boolean inputNotified=inputUa!=null && inputUa.equals(notifiedInputs.get(supply));
                Long before=edgeInputs.get(supply);
                boolean unchangedEdge=awaitingInput && !inputNotified && (before==null || before.equals(inputUa));
                if(!unchangedEdge)awaitingInput=false;
                if(inputUa!=null)inputReadings.put(supply,inputUa);
                if(protocol==null)inputValidity="UNAVAILABLE_PROTOCOL";
                // Charge-pump contributions are not qualified by this logical input-current node.
                else if(!conventional){inputValidity="UNQUALIFIED_PROTOCOL_INPUT_CURRENT";awaitingInput=false;}
                else if(unchangedEdge)inputValidity="WAITING_SOURCE_UPDATE";
                else if(uv!=null && inputUa!=null && uv>=4000000 && uv<=24000000 && inputUa>=0 && inputUa<=10000000) {
                    input=uv*(inputUa/1000000000000.0);inputValidity="DRIVER_READING";
                }else inputValidity="UNAVAILABLE_SOURCE_RANGE";
            }
        }
        if(connectionMatches && !awaitingCurrent)expectedConnected=-1;
        return map("batteryNetPowerW",net,"batteryNetValidity",batteryValidity,
                "batteryNetSign","POSITIVE_CHARGING_NEGATIVE_DISCHARGING",
                "batteryNetSource","BATTERY_LOGICAL_SUPPLY_MV_UA","batteryNetReadElapsedMs",now,
                "batteryNetObservation",notified?"POST_EDGE_DRIVER_EVENT_MATCH_TIME_UNKNOWN":"READ_TIME_ONLY_MEASUREMENT_TIME_UNKNOWN",
                "batteryNetSourceMeasurementTime",null,"batteryNetTimestampAvailable",false,
                "inputPowerW",input,"inputPowerValidity",inputValidity,"inputPowerSupply",supply,
                "inputVoltageUv",uv,"inputCurrentUa",inputUa,"inputProtocol",protocol,"inputPowerReadElapsedMs",now,
                "inputProtocolSource","/sys/class/power_supply/usb/real_type","inputProtocolScope","PLATFORM_SHARED_SINGLE_INPUT_ONLY",
                "inputPowerSourceMeasurementTime",null,"inputPowerTimestampAvailable",false);
    }

    MonitorPower() { this(new File("/sys/class/power_supply")); }
    MonitorPower(File supplies) { this.supplies = supplies; }
    private String read(String supply, String field) {
        scalarReads++;
        try { return MonitorSources.line(new File(new File(supplies,supply),field).getPath()); }
        catch (IOException | SecurityException e) { return null; }
    }
    private static boolean voltageValid(Long uv){return uv!=null && uv>=4000000 && uv<=24000000;}
    private Long number(String supply, String field) {
        try { String text=read(supply,field); return text==null?null:Long.valueOf(text); }
        catch (NumberFormatException e) { return null; }
    }
    static Double ratio(Long full, Long design) {
        if (full==null || design==null || full<100000 || design<100000
                || full>100000000 || design>100000000 || full>design*2) return null;
        return full*100.0/design;
    }
    Map<String,Object> health(long now) {
        if (health!=null && now>=healthReadAt && now-healthReadAt<HEALTH_TTL_MS) return health;
        healthReads++;
        Long full=number("battery","charge_full"), design=number("battery","charge_full_design");
        Long cycles=number("battery","cycle_count"), soh=number("battery","soh");
        Double percent=ratio(full,design);
        health=map("schema",1,"readElapsedMs",now,"ttlMs",HEALTH_TTL_MS,
                "source","/sys/class/power_supply/battery","unit","uAh",
                "chargeFullUah",full,"chargeFullDesignUah",design,
                "fccDesignPercent",percent,"confidence",percent==null?"INSUFFICIENT_DATA":"DRIVER_ESTIMATE_SAME_LOGICAL_SUPPLY",
                "cycleCount",cycles!=null&&cycles>=0&&cycles<=100000?cycles:null,
                "driverSohPercent",soh!=null&&soh>=0&&soh<=200?soh:null,
                "typicalReferenceMah",6550,"ratedReferenceMah",6400,
                "referenceRetentionQualified",false,"physicalCellCountQualified",false,
                "sourceMeasurementTime",null,"sourceTimestampAvailable",false,
                "capacityScaling",1,"independentCapacityMeasurement",false);
        healthReadAt=now;
        return health;
    }
}
