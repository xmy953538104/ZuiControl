package com.zui.server.control;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

/** Event-driven performance ranges. OEM hard thermal remains above this request. */
final class GpuPolicyController {
    interface Transport {
        int acquire(int duration, int[] pairs) throws Exception;
        int release(int handle) throws Exception;
        default boolean recoverUnowned() throws Exception { return false; }
        default String diagnostic() { return ""; }
    }
    static final class QtiTransport implements Transport {
        private Object boost;
        private Method acquire;
        private Method release;
        private boolean noLeaseProof;
        private String diagnostic = "NOT_INITIALIZED";
        private static Object invoke(Method method, Object object, Object... args) throws Exception {
            try { return method.invoke(object, args); }
            catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                if (cause instanceof LinkageError) throw (LinkageError) cause;
                throw e;
            }
        }
        private void init() throws Exception {
            if (boost != null) return;
            noLeaseProof = true; // No acquire has crossed the transport boundary.
            Class<?> manager = Class.forName("android.os.ServiceManager");
            Object service = invoke(manager.getMethod("checkService", String.class), null,
                    "vendor.qti.hardware.perf2.IPerf/default");
            if (service == null || !Boolean.TRUE.equals(invoke(
                    Class.forName("android.os.IBinder").getMethod("pingBinder"), service))) {
                diagnostic = "HAL_SERVICE_NOT_READY:beforeAcquire";
                throw new IllegalStateException("QTI HAL service not ready before acquire");
            }
            Class<?> cls = Class.forName("android.util.BoostFramework");
            Object instance = cls.getConstructor().newInstance();
            java.lang.reflect.Field perf = cls.getDeclaredField("mPerf");
            perf.setAccessible(true);
            Object client = perf.get(instance);
            if (client == null) throw new IllegalStateException("QTI client not initialized");
            Class<?> owner = client.getClass();
            if (!"com.qualcomm.qti.Performance".equals(owner.getName()))
                throw new IllegalStateException("Unsupported QTI client " + owner.getName());
            Method version = owner.getMethod("perfGetHalVer");
            Object hal;
            try { hal = invoke(version, client); }
            catch (UnsatisfiedLinkError notRegistered) {
                // Register JNI for its boot-class owner, not this services classloader.
                Method load = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
                load.setAccessible(true);
                invoke(load, Runtime.getRuntime(), owner, "qti_performance");
                hal = invoke(version, client);
            }
            if (!(hal instanceof Number) || !Double.isFinite(((Number) hal).doubleValue())
                    || ((Number) hal).doubleValue() <= 0) {
                diagnostic = "HAL_VERSION_NOT_READY:" + hal + ":beforeAcquire";
                throw new IllegalStateException("QTI HAL version not ready before acquire");
            }
            Method a = owner.getMethod("perfLockAcquire", int.class, int[].class);
            Method r = owner.getMethod("perfLockReleaseHandler", int.class);
            acquire = a;
            release = r;
            boost = client;
            diagnostic = "JNI_READY:hal=" + hal + ":client=" + owner.getName();
        }
        public int acquire(int duration, int[] pairs) throws Exception {
            try { init(); }
            catch (LinkageError e) { throw new IllegalStateException("QTI before acquire:" + e, e); }
            noLeaseProof = false;
            try { return ((Integer) invoke(acquire, boost, duration, pairs)).intValue(); }
            catch (LinkageError e) {
                noLeaseProof = e instanceof UnsatisfiedLinkError;
                throw new IllegalStateException("QTI native entry failure", e);
            }
        }
        public int release(int handle) throws Exception {
            init();
            try {
                int result = ((Integer) invoke(release, boost, handle)).intValue();
                // This OEM release-handler API leaves Performance.mHandle unchanged.
                if (result >= 0) { boost = null; noLeaseProof = true; }
                return result;
            }
            catch (LinkageError e) { throw new IllegalStateException("QTI release native entry failure", e); }
        }
        public boolean recoverUnowned() throws Exception {
            if (!noLeaseProof) return false;
            boost = null;
            init();
            return true;
        }
        public String diagnostic() { return diagnostic + ":noLeaseProof=" + noLeaseProof; }
    }

    private final Transport transport;
    private String mode = "OFF";
    private String scene = "";
    private boolean failed;
    private boolean acquireFailure;
    private int recoveryCount;
    private int handle;
    private GpuRange requested;
    private GpuRange owned;
    private int acquireCount;
    private int releaseCount;
    private int lastRelease = -1;
    private String error = "";
    private long firstAcquireMonotonicNs;
    private String firstAcquireTarget = "";

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
        acquireFailure = false;
        mode = "OFF";
        requested = null;
        error = reason;
        releaseOwned();
    }

    synchronized boolean recover() {
        if (!failed) return true;
        if (!acquireFailure || handle != 0 || owned != null || recoveryCount != 0) return false;
        try {
            if (!transport.recoverUnowned()) return false;
            recoveryCount++;
            failed = false;
            acquireFailure = false;
            error = "";
            return true;
        } catch (Exception | LinkageError e) {
            error = "recovery:" + e.getClass().getSimpleName() + ":" + e.getMessage();
            return false;
        }
    }

    private void reconcile() {
        if (handle > 0 && owned.same(requested)) return;
        if (!releaseOwned() || requested == null) return;
        try {
            if (firstAcquireMonotonicNs == 0) {
                firstAcquireMonotonicNs = System.nanoTime();
                firstAcquireTarget = scene + ":" + requested.minMHz + "-" + requested.maxMHz;
            }
            int acquired = transport.acquire(0, new int[] {0x42804000,
                    GpuRange.level(requested.minMHz), 0x42808000, GpuRange.level(requested.maxMHz)});
            acquireCount++;
            if (acquired <= 0) throw new IllegalStateException("acquire returned " + acquired);
            handle = acquired;
            owned = requested;
            error = "";
        } catch (Exception e) {
            failed = true;
            acquireFailure = true;
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
            acquireFailure = false;
            mode = "OFF";
            error = "release:" + e.getClass().getSimpleName() + ":" + e.getMessage();
            return false;
        }
    }

    synchronized String runtimeStatus() { return failed ? "DEGRADED_FAIL_SAFE" : "READY"; }

    synchronized String stateLines() {
        return "\ngpuMode=" + mode + "\ngpuHandle=" + handle
                + "\ngpuRequestedMinLevel=" + (requested == null ? -1 : GpuRange.level(requested.minMHz))
                + "\ngpuRequestedMaxLevel=" + (requested == null ? -1 : GpuRange.level(requested.maxMHz))
                + "\ngpuOwnedMinLevel=" + (owned == null ? -1 : GpuRange.level(owned.minMHz))
                + "\ngpuOwnedMaxLevel=" + (owned == null ? -1 : GpuRange.level(owned.maxMHz))
                + "\ngpuScene=" + scene
                + "\ngpuFailSafe=" + failed + "\ngpuRuntime=" + runtimeStatus() + "\ngpuLastError=" + error
                + "\ngpuAcquireCount=" + acquireCount + "\ngpuReleaseCount=" + releaseCount
                + "\ngpuLastRelease=" + lastRelease + "\ngpuTransport=QTI_PerfLock"
                + "\ngpuRecoveryCount=" + recoveryCount + "\ngpuTransportDiagnostic=" + transport.diagnostic()
                + "\ngpuFirstAcquireMonotonicNs=" + firstAcquireMonotonicNs + "\ngpuFirstAcquireTarget=" + firstAcquireTarget
                + "\ngpuPeriodicWork=0";
    }
}
