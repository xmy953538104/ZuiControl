package com.zui.server.control;

import android.zui.GpuRequestFilter;
import java.util.Arrays;

public final class GpuProofTest {
    static final int MIN = 0x42804000, MAX = 0x42808000, CPU = 0x40800000;
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    static final class Fake implements GpuPolicyController.Transport {
        int active, count, releases;
        int next = 1, acquireResult = 1, releaseResult = 0;
        int[] last;
        public int acquire(int duration, int[] pairs) {
            check(duration == 0 && active == 0);
            count++;
            last = pairs.clone();
            if (acquireResult <= 0) return acquireResult;
            active = next++;
            return active;
        }
        public int release(int handle) {
            check(handle == active && handle > 0);
            releases++;
            if (releaseResult >= 0) active = 0;
            return releaseResult;
        }
    }
    public static void main(String[] args) {
        String target = "com.zui.tassistent";
        check(GpuRequestFilter.isTarget(1000, target, target));
        String[] others = {
            "com.android.inputdevices", "com.zui.freeform.sidebar", "com.android.location.fused",
            "com.android.settings", "com.zui.keyboardupdate", "com.lenovo.EngineeringCode",
            "com.dolby.dolbyvisionservice", "com.qti.diagservices", "com.android.wallpaperbackup",
            "com.yha.factory", "com.lenovo.screensplit", "com.wapi.wapicertmanage",
            "com.android.localtransport", "com.motorola.android.providers.settings",
            "com.android.soundpicker", "com.zui.net.data.monitor", "com.qualcomm.qti.xrvd.service",
            "android", "vendor.qti.qesdk.sysservice", "com.zui.cores", "com.lenovo.ue.device",
            "com.android.dynsystem", "com.zui.ai.lens", "com.zui.safecenter", "com.zuisdk",
            "com.zui.wifip2p", "com.zui.SecretCode", "com.zui.networkaclr", "com.tbsmart.levision",
            "com.zui.pp", "com.android.keychain", "com.android.server.telecom",
            "com.android.providers.settings", "com.lenovo.penservice"
        };
        check(others.length == 34);
        for (String pkg : others) check(!GpuRequestFilter.isTarget(1000, pkg, pkg));
        check(!GpuRequestFilter.isTarget(1000, "android", "system_server"));
        check(!GpuRequestFilter.isTarget(1000, null, null));
        check(!GpuRequestFilter.isTarget(1000, target, null));
        check(!GpuRequestFilter.isTarget(1000, target, target + ":other"));
        check(!GpuRequestFilter.isTarget(10001, target, target));
        check(GpuRequestFilter.isTarget(1000, target, target)); // unbound not cached
        int[] cpu = {CPU, 7, CPU, 9};
        check(GpuRequestFilter.filter(cpu) == cpu);
        int[] mixed = {MIN, 11, CPU, 7, MAX, 8, MAX, 5, CPU, 9};
        int[] saved = mixed.clone();
        check(Arrays.equals(GpuRequestFilter.filter(mixed), cpu));
        check(Arrays.equals(saved, mixed));
        check(GpuRequestFilter.filter(new int[] {MIN, 11, MAX, 8}).length == 0);
        check(GpuRequestFilter.filter(new int[0]).length == 0);
        check(GpuRequestFilter.filter(null) == null);
        check(GpuRequestFilter.filter(new int[] {CPU}) == null);
        check(GpuRequestFilter.filter(new int[] {0x4280c000, 231})[0] == 0x4280c000);

        Fake f = new Fake();
        GpuPolicyController c = new GpuPolicyController(f);
        String game = "com.kurogame.mingchao";
        int[] opps = {231,310,366,422,500,578,629,680,720,770,834,903};
        for (int i=0;i<opps.length;i++) check(GpuRange.level(opps[i]) == 11-i);
        for (int[] bad : new int[][] {{232,903},{231,902},{903,231}}) {
            try { new GpuRange(bad[0],bad[1]); throw new AssertionError(); }
            catch (IllegalArgumentException expected) { }
        }
        String[] modes = {"powersave","balance","performance","fast"};
        int[][] expectedLevels = {{11,8},{11,5},{11,0},{5,0}};
        for (int i=0;i<modes.length;i++) {
            c.resolve(game,modes[i],null,true,true,true);
            check(f.last[1] == expectedLevels[i][0] && f.last[3] == expectedLevels[i][1]);
        }
        f = new Fake(); c = new GpuPolicyController(f);
        c.resolve(game,"powersave",null,true,true,true);
        check(Arrays.equals(f.last, new int[] {MIN, 11, MAX, 8}));
        for (int i = 0; i < 100; i++) c.resolve(game,"powersave",null,true,true,true);
        check(f.count == 1 && f.releases == 0);
        c.resolve(game,"performance",null,true,true,true);
        check(f.count == 2 && f.releases == 1 && f.last[3] == 0);
        c.resolve("com.zui.launcher","performance",null,false,true,true);
        check(f.active == 0 && f.releases == 2);
        c.resolve(game,"performance",new GpuRange(366,720),true,true,true);
        check(f.last[1] == 9 && f.last[3] == 3 && c.stateLines().contains("gpuMode=performance"));
        int before = f.count;
        c.resolve("other.app","balance",new GpuRange(366,720),true,true,true);
        check(f.count == before); // same range, new app: no reacquire
        c.resolve(game,"performance",new GpuRange(366,720),true,false,true);
        check(f.active == 0);
        c.resolve(game,"performance",new GpuRange(366,720),true,true,true);
        check(f.last[1] == 9 && f.last[3] == 3);
        c.resolve(game,"performance",null,true,true,true); // follow reset
        check(f.last[1] == 11 && f.last[3] == 0);
        c.resolve(game,"performance",null,true,true,false);
        check(f.active == 0);
        c.resolve(game,"powersave",null,true,true,true);
        c.failSafe("test");
        check(f.active == 0 && c.stateLines().contains("gpuFailSafe=true"));
        c.resolve(game,"performance",null,true,true,true);
        check(f.active == 0); // latched failsafe

        f = new Fake(); c = new GpuPolicyController(f);
        f.acquireResult = -1;
        c.resolve(game,"powersave",null,true,true,true);
        check(f.active == 0 && c.stateLines().contains("gpuFailSafe=true"));
        f = new Fake(); c = new GpuPolicyController(f);
        c.resolve(game,"powersave",null,true,true,true);
        f.releaseResult = -1;
        c.resolve(game,"performance",null,true,true,true);
        check(f.count == 1 && f.active > 0 && c.stateLines().contains("gpuFailSafe=true"));
        f.releaseResult = 1; c.resolve(game,"performance",null,true,true,false);
        check(f.active == 0);
        try { GpuRange.defaults("FAST"); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { }
        System.out.println("GPU_FILTER_POSITIVE=PASS; NEGATIVE_OTHER_UID1000=34; SYSTEM_SERVER=PASS; GPU_CONTROLLER_LIFECYCLE=PASS; IDLE_POLLING=0");
    }
}
