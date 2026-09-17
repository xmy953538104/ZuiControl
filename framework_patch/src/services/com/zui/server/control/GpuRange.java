package com.zui.server.control;

/** Frozen SM8650 OPPs; only optional MHz endpoints are persisted. */
final class GpuRange {
    private static final int[] OPP = {231,310,366,422,500,578,629,680,720,770,834,903};
    final int minMHz, maxMHz;
    GpuRange(int min, int max) {
        level(min); level(max);
        if (min > max) throw new IllegalArgumentException("GPU min > max");
        minMHz = min; maxMHz = max;
    }
    static int level(int mhz) {
        for (int i = 0; i < OPP.length; i++) if (OPP[i] == mhz) return 11 - i;
        throw new IllegalArgumentException("Unsupported GPU OPP: " + mhz);
    }
    static GpuRange defaults(String mode) {
        if ("powersave".equals(mode)) return new GpuRange(231,422);
        if ("balance".equals(mode)) return new GpuRange(231,629);
        if ("performance".equals(mode)) return new GpuRange(231,903);
        if ("fast".equals(mode)) return new GpuRange(629,903);
        throw new IllegalArgumentException("Unknown performance mode");
    }
    boolean same(GpuRange other) {
        return other != null && minMHz == other.minMHz && maxMHz == other.maxMHz;
    }
}
