package com.zui.server.control;

import java.lang.reflect.Method;

/** Event-driven, proof-only profiles. OEM hard thermal remains above this request. */
final class GpuPolicyController {
    static final String GAME = "com.kurogame.mingchao";
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
    private boolean interactive = true;
    private boolean failed;
    private int handle;
    private int maxLevel = -1;
    private int acquireCount;
    private int releaseCount;
    private int lastRelease = -1;
    private String error = "";

    GpuPolicyController(Transport transport) { this.transport = transport; }

    synchronized void configure(String requested) {
        if (!"OFF".equals(requested) && !"SAFE_CANARY".equals(requested)
                && !"FULL_DYNAMIC".equals(requested)) {
            throw new IllegalArgumentException("only OFF/SAFE_CANARY/FULL_DYNAMIC");
        }
        if (failed && !"OFF".equals(requested)) {
            throw new IllegalStateException("GPU failsafe latched; release and review required");
        }
        mode = requested;
        reconcile();
    }

    synchronized void scene(String pkg, boolean screenOn) {
        scene = pkg == null ? "" : pkg;
        interactive = screenOn;
        reconcile();
    }

    synchronized void failSafe(String reason) {
        failed = true;
        mode = "OFF";
        error = reason;
        releaseOwned();
    }

    private void reconcile() {
        int wanted = !failed && interactive && GAME.equals(scene)
                ? ("SAFE_CANARY".equals(mode) ? 8 : "FULL_DYNAMIC".equals(mode) ? 0 : -1)
                : -1;
        if (handle > 0 && maxLevel == wanted) return;
        if (!releaseOwned() || wanted < 0) return;
        try {
            // Frozen TB321FU OPP mapping: min11=231MHz, max8=422 or max0=903.
            // No arbitrary levels, frequency pin, custom opcode, or sysfs write.
            int acquired = transport.acquire(0, new int[] {0x42804000, 11, 0x42808000, wanted});
            acquireCount++;
            if (acquired <= 0) throw new IllegalStateException("acquire returned " + acquired);
            handle = acquired;
            maxLevel = wanted;
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
            maxLevel = -1;
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
                + "\ngpuRequestedMinLevel=" + (handle > 0 ? 11 : -1)
                + "\ngpuRequestedMaxLevel=" + maxLevel + "\ngpuScene=" + scene
                + "\ngpuFailSafe=" + failed + "\ngpuLastError=" + error
                + "\ngpuAcquireCount=" + acquireCount + "\ngpuReleaseCount=" + releaseCount
                + "\ngpuLastRelease=" + lastRelease + "\ngpuTransport=QTI_PerfLock"
                + "\ngpuPeriodicWork=0";
    }
}
