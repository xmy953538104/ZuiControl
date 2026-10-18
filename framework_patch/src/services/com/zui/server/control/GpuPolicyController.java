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
        private static final String NAME = "vendor.qti.hardware.perf2.IPerf/default";
        private static final String TOKEN = "vendor.qti.hardware.perf2.IPerf";
        private static final String HASH = "c5d67f27386b8feb13a185a4cdd647afbeb758ce";
        private Object service;
        private boolean noLeaseProof = true;
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
        private Object call(int code, int handle, int duration, int[] pairs) throws Exception {
            Class<?> parcel = Class.forName("android.os.Parcel");
            Object input = invoke(parcel.getMethod("obtain"), null);
            Object output = invoke(parcel.getMethod("obtain"), null);
            try {
                invoke(parcel.getMethod("writeInterfaceToken", String.class), input, TOKEN);
                if (code == 11 || code == 13) {
                    invoke(parcel.getMethod("writeInt", int.class), input, handle);
                    if (code == 11) {
                        invoke(parcel.getMethod("writeInt", int.class), input, duration);
                        invoke(parcel.getMethod("writeIntArray", int[].class), input, pairs);
                    }
                    Class<?> process = Class.forName("android.os.Process");
                    int tid = (Integer) invoke(process.getMethod("myTid"), null);
                    int pid = (Integer) invoke(process.getMethod("myPid"), null);
                    invoke(parcel.getMethod("writeIntArray", int[].class), input, new int[] {tid, pid});
                }
                // V1 proxy 515c383f: acquire11(handle,duration,pairs,reserved), release13(handle,reserved).
                if (code == 11) noLeaseProof = false;
                boolean handled = Boolean.TRUE.equals(invoke(Class.forName("android.os.IBinder").getMethod(
                        "transact", int.class, parcel, parcel, int.class), service, code, input, output, 0));
                if (!handled) {
                    if (code == 11) noLeaseProof = true; // UNKNOWN_TRANSACTION never entered the method.
                    throw new IllegalStateException("Perf2 V1 method unavailable: " + code);
                }
                invoke(parcel.getMethod("readException"), output);
                Object value = code == 13 ? Integer.valueOf(0) : invoke(parcel.getMethod(
                        code == 16777214 ? "readString" : "readInt"), output);
                if ((Integer) invoke(parcel.getMethod("dataAvail"), output) != 0)
                    throw new IllegalStateException("Perf2 V1 reply grammar: " + code + ":" + value);
                return value;
            } finally {
                invoke(parcel.getMethod("recycle"), input);
                invoke(parcel.getMethod("recycle"), output);
            }
        }
        private void init() throws Exception {
            if (service != null && Boolean.TRUE.equals(invoke(
                    Class.forName("android.os.IBinder").getMethod("pingBinder"), service))) return;
            if (!noLeaseProof) throw new IllegalStateException("Previous Perf2 ownership unresolved");
            service = null;
            Class<?> manager = Class.forName("android.os.ServiceManager");
            service = invoke(manager.getMethod("checkService", String.class), null, NAME);
            if (service == null || !Boolean.TRUE.equals(invoke(
                    Class.forName("android.os.IBinder").getMethod("pingBinder"), service))) {
                diagnostic = "HAL_SERVICE_NOT_READY:beforeAcquire";
                throw new IllegalStateException("QTI HAL service not ready before acquire");
            }
            String token = (String) invoke(Class.forName("android.os.IBinder").getMethod("getInterfaceDescriptor"), service);
            if (!TOKEN.equals(token)) {
                service = null;
                diagnostic = "UNSUPPORTED_PERF2_DESCRIPTOR:" + token;
                throw new IllegalStateException(diagnostic);
            }
            int version = (Integer) call(16777215, 0, 0, null);
            String hash = (String) call(16777214, 0, 0, null);
            if (version != 1 || !HASH.equals(hash)) {
                diagnostic = "UNSUPPORTED_PERF2_ABI:" + version + ":" + hash;
                service = null;
                throw new IllegalStateException(diagnostic);
            }
            diagnostic = "AIDL_V1_READY:hash=" + hash;
        }
        public int acquire(int duration, int[] pairs) throws Exception {
            init();
            return (Integer) call(11, 0, duration, pairs);
        }
        public int release(int handle) throws Exception {
            if (service == null) throw new IllegalStateException("Original Perf2 owner unavailable");
            call(13, handle, 0, null); // This V1 release returns a void status ACK.
            noLeaseProof = true;
            return 0;
        }
        public boolean recoverUnowned() throws Exception {
            if (!noLeaseProof) return false;
            service = null;
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
    private boolean releaseUncertain;
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
        if (releaseUncertain) return false;
        try {
            lastRelease = transport.release(handle);
            releaseCount++;
            if (lastRelease < 0) throw new IllegalStateException("release returned " + lastRelease);
            handle = 0;
            owned = null;
            releaseUncertain = false;
            return true;
        } catch (Exception e) {
            // Keep the uncertain handle visible and never acquire a second one.
            failed = true;
            releaseUncertain = true;
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
                + "\ngpuReleaseUncertain=" + releaseUncertain
                + "\ngpuFirstAcquireMonotonicNs=" + firstAcquireMonotonicNs + "\ngpuFirstAcquireTarget=" + firstAcquireTarget
                + "\ngpuPeriodicWork=0";
    }
}
