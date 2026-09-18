package com.zui.server.control;

/** Pure lifecycle selection, independently testable without foreground polling. */
final class MonitorLifecycle {
    boolean manual;
    private String manualTarget = "";
    void setManual(boolean enabled) { manual = enabled; manualTarget = ""; }
    String select(String target, boolean eligible, boolean automatic) {
        if (!eligible) return "";
        if (manual && manualTarget.isEmpty()) manualTarget = target;
        return automatic || (manual && manualTarget.equals(target)) ? target : "";
    }
}
