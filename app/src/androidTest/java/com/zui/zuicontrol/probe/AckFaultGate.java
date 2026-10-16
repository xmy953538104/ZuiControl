package com.zui.zuicontrol.probe;

/** Test APK only: one exact identity may be dropped or delayed once. */
final class AckFaultGate {
    private String id="",sha="";
    private int action;
    private boolean consumed;
    synchronized void arm(String id,String sha,int action) {
        if(id.isEmpty()||id.length()>64||!sha.matches("[0-9a-f]{64}")||(action!=1&&action!=2))throw new IllegalArgumentException("fault identity");
        this.id=id;this.sha=sha;this.action=action;consumed=false;
    }
    synchronized int take(String id,String sha) {
        if(consumed||!this.id.equals(id)||!this.sha.equals(sha))return 0;
        consumed=true;return action;
    }
    synchronized boolean consumed(){return consumed;}
}
