package com.zui.server.control;

import java.lang.reflect.Method;

/** Event-driven performance ranges. OEM hard thermal remains above this request. */
final class GpuPolicyController {
    interface Transport {
        int acquire(int duration, int[] pairs) throws Exception;
        int release(int handle) throws Exception;
    }
    static final class QtiTransport implements Transport {
        private Object boost;
        private Method acquire;
        private Method release;
        private void init() throws Exception {
            if (boost != null) return;
            Class<?> cls = Class.forName("android.util.BoostFramework");
            Object instance = cls.getConstructor().newInstance();
            Method a = cls.getMethod("perfLockAcquire", int.class, int[].class);
            Method r = cls.getMethod("perfLockReleaseHandler", int.class);
            acquire = a;
            release = r;
            boost = instance;
        }
        public int acquire(int duration, int[] pairs) throws Exception {
            init();
            return ((Integer) acquire.invoke(boost, duration, pairs)).intValue();
        }
        public int release(int handle) throws Exception {
            init();
            return ((Integer) release.invoke(boost, handle)).intValue();
        }
    }

    private final Transport transport;
    private String mode = "OFF";
    private String scene = "";
    private boolean failed;
    private int handle;
    private GpuRange requested;
    private GpuRange owned;
    private int acquireCount;
    private int releaseCount;
    private int lastRelease = -1;
    private String error = "";

    GpuPolicyController(Transport transport) { this.transport = transport; }

    synchronized void resolve(String pkg, String performanceMode, GpuRange override,
            boolean eligible, boolean screenOn, boolean enabled) {
        scene = pkg == null ? "" : pkg;
        mode = performanceMode;
        requested = eligible && screenOn && enabled && !failed
                ? (override == null ? GpuRange.defaults(performanceMode) : override) : null;
        reconcile();
    }

    synchronized void failSafe(String reason) {
        failed = true;
        mode = "OFF";
        requested = null;
        error = reason;
        releaseOwned();
    }

    private void reconcile() {
        if (handle > 0 && owned.same(requested)) return;
        if (!releaseOwned() || requested == null) return;
        try {
            int acquired = transport.acquire(0, new int[] {0x42804000,
                    GpuRange.level(requested.minMHz), 0x42808000, GpuRange.level(requested.maxMHz)});
            acquireCount++;
            if (acquired <= 0) throw new IllegalStateException("acquire returned " + acquired);
            handle = acquired;
            owned = requested;
            error = "";
        } catch (Exception e) {
            failed = true;
            mode = "OFF";
            error = "acquire:" + e.getClass().getSimpleName() + ":" + e.getMessage();
        }
    }

    private boolean releaseOwned() {
        if (handle <= 0) return true;
        try {
            lastRelease = transport.release(handle);
            releaseCount++;
            if (lastRelease < 0) throw new IllegalStateException("release returned " + lastRelease);
            handle = 0;
            owned = null;
            return true;
        } catch (Exception e) {
            // Keep the uncertain handle visible and never acquire a second one.
            failed = true;
            mode = "OFF";
            error = "release:" + e.getClass().getSimpleName() + ":" + e.getMessage();
            return false;
        }
    }

    synchronized String stateLines() {
        return "\ngpuMode=" + mode + "\ngpuHandle=" + handle
                + "\ngpuRequestedMinLevel=" + (requested == null ? -1 : GpuRange.level(requested.minMHz))
                + "\ngpuRequestedMaxLevel=" + (requested == null ? -1 : GpuRange.level(requested.maxMHz))
                + "\ngpuOwnedMinLevel=" + (owned == null ? -1 : GpuRange.level(owned.minMHz))
                + "\ngpuOwnedMaxLevel=" + (owned == null ? -1 : GpuRange.level(owned.maxMHz))
                + "\ngpuScene=" + scene
                + "\ngpuFailSafe=" + failed + "\ngpuLastError=" + error
                + "\ngpuAcquireCount=" + acquireCount + "\ngpuReleaseCount=" + releaseCount
                + "\ngpuLastRelease=" + lastRelease + "\ngpuTransport=QTI_PerfLock"
                + "\ngpuPeriodicWork=0";
    }
}
