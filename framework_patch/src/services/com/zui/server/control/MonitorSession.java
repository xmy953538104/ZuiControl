package com.zui.server.control;

/** Recording is a user-owned lease, independent from visibility and process lifetime. */
final class MonitorSession {
    static final int OFF = 0, FULL = 1, FPS = 2;
    int mode;
    String foreground = "", recordingPackage = "";
    int user, recordingUser;
    boolean eligible, recordEligible, processAvailable = true;
    long armTime = -1;
    String armPackage = "";

    void scene(String pkg, int userId, boolean valid) {
        scene(pkg,userId,valid,valid);
    }
    void scene(String pkg, int userId, boolean visible, boolean canRecord) {
        if (!pkg.equals(foreground) || user != userId || !canRecord) cancelArm();
        foreground = pkg; user = userId; eligible = visible; recordEligible = canRecord;
    }
    void toggle(int requested) {
        mode = mode == requested ? OFF : requested;
        cancelArm();
    }
    boolean recording() { return !recordingPackage.isEmpty(); }
    boolean recordingActive() {
        return recording() && eligible && recordEligible && mode != OFF
                && recordingUser == user && recordingPackage.equals(foreground);
    }
    boolean sampling() { return eligible && mode != OFF; }
    boolean arm(long now) {
        if (mode != FULL || !eligible || !recordEligible || recording()) return false;
        armTime = now; armPackage = foreground; return true;
    }
    boolean canStart(long now) {
        return armTime >= 0 && now - armTime >= 2000 && mode == FULL && eligible && recordEligible
                && !recording() && foreground.equals(armPackage);
    }
    void started() { recordingPackage = foreground; recordingUser = user; processAvailable = true; cancelArm(); }
    void stop() { recordingPackage = ""; cancelArm(); }
    void cancelArm() { armTime = -1; armPackage = ""; }
    String recordingState() { return !recording() ? "IDLE" : recordingActive() && processAvailable ? "RECORDING" : "PAUSED"; }
}
