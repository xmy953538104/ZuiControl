package android.zui;

import android.util.Log;

/** ROM-qualified OEM host only. Does not change package or certificate trust. */
public final class GpuRequestFilter {
    private static final String TARGET = "com.zui.tassistent";
    private static boolean sUnboundReported;

    private GpuRequestFilter() { }

    public static boolean isTarget(int uid, String packageName, String processName) {
        if (uid == 1000 && (packageName == null || processName == null)) {
            synchronized (GpuRequestFilter.class) {
                if (!sUnboundReported) {
                    Log.w("ZuiGpuFilter", "UNBOUND_HOST_IDENTITY: qualification required; pass through");
                    sUnboundReported = true;
                }
            }
        }
        // Never cache a null identity, use Binder caller identity, or match a prefix.
        return uid == 1000 && TARGET.equals(packageName) && TARGET.equals(processName);
    }

    /** null means malformed; empty means GPU-only. Neither may reach native acquire. */
    public static int[] filter(int[] pairs) {
        if (pairs == null || (pairs.length & 1) != 0) return null;
        int kept = 0;
        for (int i = 0; i < pairs.length; i += 2) {
            if (!isGpu(pairs[i])) kept += 2;
        }
        if (kept == pairs.length) return pairs;
        int[] result = new int[kept];
        int out = 0;
        for (int i = 0; i < pairs.length; i += 2) {
            if (!isGpu(pairs[i])) {
                result[out++] = pairs[i];
                result[out++] = pairs[i + 1];
            }
        }
        return result;
    }

    private static boolean isGpu(int opcode) {
        return opcode == 0x42804000 || opcode == 0x42808000;
    }
}
