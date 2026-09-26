package com.zui.server.control;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.IOException;
import static com.zui.server.control.PolicyJson.*;

public final class AppPolicyFixture {
    static int checks;
    static void check(boolean b, String reason) { if (!b) throw new AssertionError(reason); checks++; }
    interface Work { void run() throws Exception; }
    static void rejects(Work work) throws Exception {
        try { work.run(); } catch (IllegalArgumentException | IllegalStateException | IOException expected) { checks++; return; }
        throw new AssertionError("accepted invalid input");
    }
    static byte[] b(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    static final class Crash extends Error { private static final long serialVersionUID = 1; }
    static final class Disk implements AppPolicyStore.Storage {
        final Map<String,byte[]> data = new TreeMap<>(); String crash = ""; boolean after;int writes,crashAt=-1;
        public byte[] read(String name) { return data.getOrDefault(name, new byte[0]).clone(); }
        public void write(String name, byte[] bytes) {
            writes++;if(writes==crashAt&&!after)throw new Crash();
            if (crash.equals(name) && !after) { crash = ""; throw new Crash(); }
            data.put(name, bytes.clone());if(writes==crashAt&&after)throw new Crash(); if (crash.equals(name)) { crash = ""; throw new Crash(); }
        }
    }
    static final class Owner implements AppPolicyStore.Owner {
        long generation; int prepares, applies; boolean failOnce, unavailable;
        public void prepare(AppPolicyStore.State next, String tx) throws IOException { if (unavailable) throw new IOException("unreachable"); prepares++; }
        public void apply(AppPolicyStore.State next, String tx) throws IOException {
            if (unavailable || failOnce) { failOnce = false; throw new IOException("apply"); } applies++; generation = next.generation;
        }
    }
    static final class Rules implements SettingsBackup.Rules {
        byte[] data=b("schema=2\n");String pending="",generation="g000000000000000000000001";byte[] before,target;boolean fail,prepareFail;
        public Map<String,Object> snapshot(){return map("data",Base64.getEncoder().encodeToString(data),"generation",generation,"pending",pending);}
        public void prepare(String tx,String expected,byte[] next){if(prepareFail){prepareFail=false;throw new IllegalStateException("native prepare failed before lease");}require(pending.isEmpty()&&generation.equals(expected),"rule CAS");pending=tx;before=data.clone();target=next.clone();}
        public void apply(String tx,byte[] wanted,boolean rollback)throws IOException {
            require(tx.equals(pending)&&Arrays.equals(wanted,rollback?before:target),"rule stage binding");
            data=wanted.clone();if(fail){fail=false;throw new IOException("rule ACK");}
        }
        public void finish(String tx){require(pending.isEmpty()||pending.equals(tx),"rule release identity");pending="";}
    }
    static void backupTests(AppPolicyStore.State initial)throws Exception{
        Disk disk=new Disk();disk.write(AppPolicyStore.ACTIVE,initial.bytes());Owner owner=new Owner();Rules rules=new Rules();
        AppPolicyStore store=new AppPolicyStore(disk);SettingsBackup backup=new SettingsBackup(store);
        byte[] archive=backup.export(rules,"host-fixture",0),again=backup.export(rules,"host-fixture",0);
        check(Arrays.equals(archive,again),"deterministic settings ZIP");
        Map<String,byte[]> entries=SettingsBackup.validate(archive,initial.users);
        check(entries.keySet().equals(new HashSet<>(Arrays.asList(SettingsBackup.NAMES))),"allowlisted entries only");
        Map<Integer,Long> other=new TreeMap<>(initial.users);other.put(0,999L);rejects(()->SettingsBackup.validate(archive,other));
        byte[] damaged=archive.clone();damaged[50]^=1;rejects(()->SettingsBackup.validate(damaged,initial.users));
        rejects(()->SettingsBackup.validate(Arrays.copyOf(archive,archive.length-1),initial.users));
        byte[] trailing=Arrays.copyOf(archive,archive.length+1);rejects(()->SettingsBackup.validate(trailing,initial.users));
        Map<String,Object> prefs=object(parse(backup.prefs()));object(array(prefs.get("users")).get(0)).put("circle_x",1.1);
        rejects(()->SettingsBackup.preferences(bytes(prefs),initial.users));
        object(array(prefs.get("users")).get(0)).put("circle_x",0.25);object(array(prefs.get("users")).get(0)).put("desiredON",true);
        rejects(()->SettingsBackup.preferences(bytes(prefs),initial.users));
        AppPolicyStore.State next=initial.copy();next.globals.put(0,new AppPolicyStore.Row(60,"fast",629,903));
        Map<String,Object> nextPrefs=object(parse(backup.prefs()));object(array(nextPrefs.get("users")).get(0)).put("circle_x",0.25);
        byte[] wanted=SettingsBackup.archive(next,map("data",Base64.getEncoder().encodeToString(b("schema=2\n# next\n")),"generation",rules.generation),bytes(nextPrefs),"fixture",0);
        String tx="000000000000000000000001";backup.restore(wanted,tx,owner,rules);
        check(store.current.global(0).refreshHz==60&&!backup.busy()&&rules.pending.isEmpty(),"whole settings applied");
        long gen=store.current.generation;backup.restore(wanted,tx,owner,rules);check(store.current.generation==gen,"restore replay no double commit");
        rejects(()->backup.restore(archive,tx,owner,rules));
        byte[] before=store.current.bytes(),priorRules=rules.data.clone(),priorPrefs=backup.prefs();
        rules.prepareFail=true;rejects(()->backup.restore(archive,"000000000000000000000004",owner,rules));
        check(Arrays.equals(store.current.bytes(),before)&&Arrays.equals(rules.data,priorRules)&&!backup.busy(),"pre-commit failure preserves all configuration");
        rules.fail=true;rejects(()->backup.restore(archive,"000000000000000000000002",owner,rules));
        check(store.current.global(0).refreshHz==60&&Arrays.equals(rules.data,priorRules)&&Arrays.equals(backup.prefs(),priorPrefs)&&!backup.busy(),"failed rule ACK restores complete previous configuration");
        // Crash after authoritative policy rename, before complete settings ACK; restart uses the durable decision.
        disk.crash=AppPolicyStore.ACTIVE;disk.after=true;
        try{backup.restore(archive,"000000000000000000000003",owner,rules);throw new AssertionError("crash not injected");}catch(Crash expected){}
        AppPolicyStore reopened=new AppPolicyStore(disk);SettingsBackup recovery=new SettingsBackup(reopened);check(recovery.busy(),"crash blocks mutations");
        recovery.recover(owner,rules);
        check(reopened.current.global(0).refreshHz==60&&Arrays.equals(rules.data,priorRules)&&Arrays.equals(recovery.prefs(),priorPrefs)&&!recovery.busy(),"crash whole rollback");
        check(!disk.data.containsKey("monitor.db")&&!disk.data.containsKey("desiredON"),"backup never touches records or desired state");
        // Every coordinator/storage write boundary, both before and after durability.
        Disk probe=new Disk();probe.write(AppPolicyStore.ACTIVE,initial.bytes());int begin=probe.writes;
        new SettingsBackup(new AppPolicyStore(probe)).restore(wanted,"000000000000000000000010",new Owner(),new Rules());
        int boundaries=probe.writes-begin;
        for(int boundary=1;boundary<=boundaries;boundary++)for(boolean afterWrite:new boolean[]{false,true}){
            Disk broken=new Disk();broken.write(AppPolicyStore.ACTIVE,initial.bytes());
            broken.crashAt=broken.writes+boundary;broken.after=afterWrite;Rules nativeOwner=new Rules();
            SettingsBackup attempt=new SettingsBackup(new AppPolicyStore(broken));
            try{attempt.restore(wanted,"000000000000000000000010",new Owner(),nativeOwner);throw new AssertionError("missing crash "+boundary);}catch(Crash expected){}
            broken.crashAt=-1;
            SettingsBackup restarted=new SettingsBackup(new AppPolicyStore(broken));restarted.recover(new Owner(),nativeOwner);
            boolean applied=restarted.policy.current.global(0).refreshHz==60;
            check(!restarted.busy()&&nativeOwner.pending.isEmpty(),"resolved crash boundary "+boundary);
            check(Arrays.equals(nativeOwner.data,applied?b("schema=2\n# next\n"):b("schema=2\n")),"whole rule generation after crash "+boundary);
            check(Arrays.equals(restarted.prefs(),applied?bytes(nextPrefs):bytes(SettingsBackup.defaultPreferences(initial.users))),"whole prefs after crash "+boundary);
        }
        System.out.println("SETTINGS_BACKUP_ROUNDTRIP_REJECTION_ACK_FAILURE_CRASH_ROLLBACK=PASS");
    }
    public static void main(String[] args) throws Exception {
        Map<Integer,Long> users = new TreeMap<>(); users.put(0, 0L); users.put(10, 42L);
        StringBuilder profiles = new StringBuilder("version=1\ndefault|0|90|0|DISPLAY_ONLY\ngpuGlobal|0|performance|834|903\n");
        StringBuilder perapp = new StringBuilder("- powersave\n"), settings = new StringBuilder();
        for (int bits = 1; bits <= 7; bits++) {
            String pkg = "org.example.a" + bits;
            if ((bits & 1) != 0) profiles.append("pkg|0|").append(pkg).append("|120|0|DISPLAY_ONLY\n");
            if ((bits & 2) != 0) { perapp.append(pkg).append(" performance\n"); settings.append(pkg).append("|performance\n"); }
            if ((bits & 4) != 0) profiles.append("gpu|0|").append(pkg).append("|500|680\n");
        }
        profiles.append("pkg|0|com.zui.zuicontrol|90|0|DISPLAY_ONLY\npkg|0|org.example.home|90|0|DISPLAY_ONLY\n");
        Set<String> quarantine = new HashSet<>(Arrays.asList("org.example.home", "com.android.systemui", "android"));
        byte[] raw = b(profiles.toString()), saved = b("balance\n"), rules = b(perapp.toString());
        AppPolicyStore.Migration m = AppPolicyStore.migrate(raw, saved, rules, "balance", settings.toString(), users, quarantine);
        AppPolicyStore.State state = m.state;
        backupTests(state);
        for (int bits = 1; bits <= 7; bits++) {
            AppPolicyStore.Row r = state.apps.get("0:org.example.a" + bits);
            check(r != null && r.refreshHz == 120, "complete refresh/equal global");
            check(r.uperfMode.equals((bits & 2) != 0 ? "performance" : "balance"), "seven Uperf combinations");
            check(r.gpuMinMHz == ((bits & 4) != 0 ? 500 : (bits & 2) != 0 ? 834 : 231), "GPU precedence");
            check(r.gpuMaxMHz == ((bits & 4) != 0 ? 680 : (bits & 2) != 0 ? 903 : 629), "OLD fallback before new default");
        }
        check(state.range(0, "balance").maxMHz == 578 && state.range(0, "performance").minMHz == 834, "new factory only absent");
        check(state.apps.containsKey("10:org.example.a2") && !state.apps.containsKey("10:org.example.a1"), "package-only fanout");
        check(!state.apps.containsKey("0:org.example.home") && state.apps.containsKey("0:com.zui.zuicontrol"), "HOME quarantine and own app");
        check(state.global(0).refreshHz == 120, "effective legacy default");
        check(Arrays.equals(state.bytes(), AppPolicyStore.State.parse(state.bytes()).bytes()), "canonical roundtrip");
        AppPolicyStore.State changed = AppPolicyStore.change(state, 1, 0, "", "refresh", 60, "", 0, 0, true);
        check(changed.global(0).refreshHz == 60 && changed.apps.get("0:org.example.a1").refreshHz == 120, "global leaves explicit snapshots");
        changed = AppPolicyStore.change(changed, 2, 0, "org.example.a7", "mode", 0, "performance", 0, 0, false);
        check(changed.apps.get("0:org.example.a7").gpuMinMHz == 834, "mode RESELECT resets manual GPU");
        check(changed.apps.get("10:org.example.a7").gpuMinMHz == 231, "multi-user isolation");
        AppPolicyStore.State resetGpu = AppPolicyStore.change(state, 1, 0, "org.example.a7", "gpuDefault", 0, "", 0, 0, false);
        check(resetGpu.apps.get("0:org.example.a7").gpuMinMHz == 834 && resetGpu.apps.get("0:org.example.a7").uperfMode.equals("performance") && resetGpu.apps.get("0:org.example.a7").refreshHz == 120, "GPU reset preserves other dimensions and explicit row");
        AppPolicyStore.State defaults = AppPolicyStore.change(state, 1, 0, "", "defaultGpu", 0, "balance", 310, 578, true);
        check(defaults.global(0).gpuMinMHz == 310 && defaults.apps.get("0:org.example.a1").gpuMinMHz == 231, "changed global GPU leaves explicit app snapshots");
        AppPolicyStore.State removed = AppPolicyStore.change(changed, 3, 0, "org.example.a7", "delete", 0, "", 0, 0, false);
        check(!removed.apps.containsKey("0:org.example.a7") && removed.resolved(0, "org.example.a7").refreshHz == 60, "whole row delete");
        rejects(() -> AppPolicyStore.change(state, 0, 0, "org.example.a1", "refresh", 90, "", 0, 0, false));
        rejects(() -> AppPolicyStore.migrate(b(profiles + "gpu|0|org.example.a4|500|680\n"), saved, rules, "balance", settings.toString(), users, quarantine));
        rejects(() -> AppPolicyStore.migrate(raw, saved, rules, "powersave", settings.toString(), users, quarantine));
        rejects(() -> AppPolicyStore.migrate(raw, saved, rules, "balance", "", users, quarantine));
        rejects(() -> parse(b("{\"a\":1,\"a\":2}"))); rejects(() -> parse(b("{\"a\":NaN}"))); rejects(() -> parse(b("{\"a\":1,}")));
        rejects(() -> AppPolicyStore.Row.from(map("refreshHz", 120, "uperfMode", "balance", "gpuMinMHz", 333, "gpuMaxMHz", 903)));
        check(AppPolicyStore.actionScope(3, "org.example.home", 3, "org.example.home", "org.example.home", "FOREGROUND", "org.example.home", "mode", true), "HOME quick global");
        rejects(() -> AppPolicyStore.actionScope(3, "org.example.a1", 4, "org.example.a2", "org.example.a1", "FOREGROUND", "org.example.home", "refresh", true));
        rejects(() -> AppPolicyStore.actionScope(3, "org.example.a1", 3, "org.example.a1", "org.example.a2", "FOREGROUND", "org.example.home", "refresh", true));
        Disk disk = new Disk(); Owner owner = new Owner(); AppPolicyStore store = new AppPolicyStore(disk);
        store.migrate(m, raw, saved, rules, owner); int applies = owner.applies;
        new AppPolicyStore(disk).migrate(m, raw, saved, rules, owner); check(owner.applies == applies, "migration idempotent");
        check(Arrays.equals(raw, disk.read("legacy-profiles.prop")) && Arrays.equals(rules, disk.read("legacy-perapp.txt")), "exact legacy backup");
        AppPolicyStore.State next = AppPolicyStore.change(store.current, 1, 0, "org.example.a1", "refresh", 90, "", 0, 0, false);
        owner.failOnce = true; rejects(() -> store.commit(next, owner));
        check(store.current.generation == 3 && store.current.apps.get("0:org.example.a1").refreshHz == 120 && owner.generation == 3, "whole previous generation rollback with ACK");
        for (String stage : Arrays.asList(AppPolicyStore.JOURNAL, AppPolicyStore.ACTIVE)) for (boolean after : Arrays.asList(false, true)) {
            Disk crashDisk = new Disk(); crashDisk.data.putAll(disk.data); AppPolicyStore crashStore = new AppPolicyStore(crashDisk);
            AppPolicyStore.State target = AppPolicyStore.change(crashStore.current, 3, 0, "org.example.a1", "refresh", 90, "", 0, 0, false);
            crashDisk.crash = stage; crashDisk.after = after;
            try { crashStore.commit(target, owner); throw new AssertionError("crash hook"); } catch (Crash expected) { }
            AppPolicyStore recovered = new AppPolicyStore(crashDisk); recovered.recover(owner);
            check(!recovered.recoveryRequired && (recovered.current.generation == 3 || recovered.current.generation == 4), "crash hash reconciliation");
            check(Arrays.equals(raw, crashDisk.read("legacy-profiles.prop")), "crash preserves legacy");
        }
        for (String stage : Arrays.asList("legacy-profiles.prop", "legacy-saved.txt", "legacy-perapp.txt", "policy-migration-target.json", "policy-migration.json", AppPolicyStore.ACTIVE)) for (boolean after : Arrays.asList(false, true)) {
            Disk interrupted = new Disk(); interrupted.crash = stage; interrupted.after = after;
            try { new AppPolicyStore(interrupted).migrate(AppPolicyStore.migrate(raw, saved, rules, "balance", settings.toString(), users, quarantine), raw, saved, rules, owner); throw new AssertionError("migration crash hook"); } catch (Crash expected) { }
            AppPolicyStore resumed = new AppPolicyStore(interrupted);
            resumed.migrate(AppPolicyStore.migrate(raw, saved, rules, "balance", settings.toString(), users, quarantine), raw, saved, rules, owner);
            check(resumed.current.generation == 1 && !resumed.recoveryRequired, "initial migration crash recovery");
            check(Arrays.equals(raw, interrupted.read("legacy-profiles.prop")), "initial migration exact old bytes");
        }
        Map<Integer,Long> newUsers = new TreeMap<>(users); newUsers.put(11, 43L);
        AppPolicyStore.State newUser = AppPolicyStore.reconcileUsers(state, newUsers);
        check(newUser.generation == 2 && newUser.resolved(11, "org.example.a2").uperfMode.equals("balance") && !newUser.apps.containsKey("11:org.example.a2"), "new user inherits global only");
        check(newUser.range(11,"performance").minMHz == 834, "new user configured global defaults");
        Map<Integer,Long> reused = new TreeMap<>(users); reused.put(10, 100L); rejects(() -> AppPolicyStore.reconcileUsers(state, reused));
        AppPolicyStore.State unresolved = state.copy(); unresolved.apps.put("11:org.example.a2", state.global(0));
        rejects(() -> AppPolicyStore.reconcileUsers(unresolved, newUsers));
        check(unresolved.apps.containsKey("11:org.example.a2"), "unresolved identity evidence preserved");
        rejects(() -> AppPolicyStore.migrate(raw, saved, b(perapp + "- powersave\n"), "balance", settings.toString(), users, quarantine));
        // PREPARE never changes authoritative bytes; unavailable owners inhibit further writes.
        Disk held = new Disk(); held.data.putAll(disk.data); AppPolicyStore heldStore = new AppPolicyStore(held);
        AppPolicyStore.State heldNext = AppPolicyStore.change(heldStore.current, 3, 0, "org.example.a1", "refresh", 90, "", 0, 0, false);
        AppPolicyStore.Owner unreachable = new AppPolicyStore.Owner() {
            public void prepare(AppPolicyStore.State nextState, String tx) { check(Arrays.equals(held.read(AppPolicyStore.ACTIVE), heldStore.current.bytes()), "prepare has no visible apply"); }
            public void apply(AppPolicyStore.State nextState, String tx) throws IOException { throw new IOException("owner died"); }
        };
        rejects(() -> heldStore.commit(heldNext, unreachable));
        check(heldStore.recoveryRequired, "unreachable rollback holds mutations");
        rejects(() -> heldStore.commit(heldNext, owner)); heldStore.recover(owner);
        check(!heldStore.recoveryRequired && heldStore.current.generation == 5, "recover whole rollback after owner returns");
        System.out.println("UNIFIED_POLICY_PRODUCTION_FIXTURE_PASS checks=" + checks);
    }
}
