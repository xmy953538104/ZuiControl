package com.zui.server.control;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import static com.zui.server.control.PolicyJson.*;

/** TB321FU logical battery supply. Read-only; owned by the existing Monitor collector. */
class MonitorPower {
    static final long HEALTH_TTL_MS = 300000;
    private final File supplies;
    private Map<String,Object> health;
    private long healthReadAt = -1;
    long scalarReads, healthReads;
    private long lastCurrent = Long.MIN_VALUE, edgeCurrent = Long.MIN_VALUE;
    private boolean awaitingCurrent;
    private int expectedConnected = -1;
    private String lastInput = "", edgeInput = "";

    void edge(boolean connected) {
        expectedConnected=connected?1:0;
        edgeCurrent=lastCurrent;awaitingCurrent=true;edgeInput=lastInput;
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
        if(awaitingCurrent && connectionMatches && netWatts(plugged,status,mv,ua)!=null && ua!=edgeCurrent
                && (edgeCurrent!=Long.MIN_VALUE || ua!=0))awaitingCurrent=false;
        if(ua!=Long.MIN_VALUE)lastCurrent=ua;
        Double net=connectionMatches&&!awaitingCurrent?netWatts(plugged,status,mv,ua):null;
        String batteryValidity=!connectionMatches?"WAITING_POWER_STATE":awaitingCurrent?"WAITING_SOURCE_UPDATE":net==null?"UNAVAILABLE_SOURCE_OR_DIRECTION":"DRIVER_READING";
        String inputValidity=plugged==0?"DISCONNECTED":"UNAVAILABLE_PORT_MAPPING",supply="";
        Long uv=null, inputUa=null;
        Double input=null;
        if(plugged>0 && connectionMatches) {
            Long one=number("ucsi-source-psy-soc:qcom,pmic_glink:qcom,ucsi1","online");
            Long two=number("ucsi-source-psy-soc:qcom,pmic_glink:qcom,ucsi2","online");
            if(one!=null && two!=null && ((one==1 && two==0)||(one==0 && two==1))) {
                supply=one==1?"usb":"wireless";
                uv=number(supply,"voltage_now");inputUa=number(supply,"current_now");
                String fingerprint=supply+":"+uv+":"+inputUa;
                boolean unchangedEdge=expectedConnected==1 && fingerprint.equals(edgeInput);
                lastInput=fingerprint;
                String protocol=read("usb","real_type");
                if(protocol==null)inputValidity="UNAVAILABLE_PROTOCOL";
                // Charge-pump contributions are not qualified by this logical input-current node.
                else if(!protocol.equals("SDP") && !protocol.equals("CDP") && !protocol.equals("DCP"))inputValidity="UNQUALIFIED_PROTOCOL_INPUT_CURRENT";
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
                "batteryNetSourceMeasurementTime",null,"batteryNetTimestampAvailable",false,
                "inputPowerW",input,"inputPowerValidity",inputValidity,"inputPowerSupply",supply,
                "inputVoltageUv",uv,"inputCurrentUa",inputUa,"inputPowerReadElapsedMs",now,
                "inputPowerSourceMeasurementTime",null,"inputPowerTimestampAvailable",false);
    }

    MonitorPower() { this(new File("/sys/class/power_supply")); }
    MonitorPower(File supplies) { this.supplies = supplies; }
    String read(String supply, String field) {
        scalarReads++;
        try { return MonitorSources.line(new File(new File(supplies,supply),field).getPath()); }
        catch (IOException | SecurityException e) { return null; }
    }
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
