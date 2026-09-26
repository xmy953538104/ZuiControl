package com.zui.server.control;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import static com.zui.server.control.PolicyJson.*;

/** Sole policy authority. Storage and owner calls are durable, bounded transaction boundaries. */
final class AppPolicyStore {
    static final String[] MODES = {"powersave", "balance", "performance", "fast"};
    static final String ACTIVE = "policy-v2.json", JOURNAL = "policy-transaction.json";
    interface Storage {
        byte[] read(String name) throws Exception; // absent => empty; never silently discard corrupt bytes
        void write(String name, byte[] bytes) throws Exception;
    }
    interface Owner {
        void prepare(State next, String transaction) throws Exception;
        void apply(State next, String transaction) throws Exception;
    }
    static final class Row {
        final int refreshHz, gpuMinMHz, gpuMaxMHz;
        final String uperfMode;
        Row(int hz, String mode, int min, int max) {
            require(Arrays.asList(60, 90, 120, 144, 165).contains(hz), "refreshHz");
            require(mode(mode), "uperfMode"); new GpuRange(min, max);
            refreshHz = hz; uperfMode = mode; gpuMinMHz = min; gpuMaxMHz = max;
        }
        Object json() { return map("refreshHz", refreshHz, "uperfMode", uperfMode, "gpuMinMHz", gpuMinMHz, "gpuMaxMHz", gpuMaxMHz); }
        static Row from(Object value) {
            Map<String,Object> r = object(value); keys(r, "refreshHz", "uperfMode", "gpuMinMHz", "gpuMaxMHz");
            return new Row(intValue(r.get("refreshHz")), string(r.get("uperfMode")), intValue(r.get("gpuMinMHz")), intValue(r.get("gpuMaxMHz")));
        }
    }
    static final class State {
        long generation;
        final Map<Integer,Long> users = new TreeMap<>();
        final Map<Integer,Row> globals = new TreeMap<>();
        final Map<String,Row> apps = new TreeMap<>();
        final Map<String,GpuRange> defaults = new TreeMap<>();
        String migration = "";
        State copy() {
            State s = new State(); s.generation = generation; s.users.putAll(users); s.globals.putAll(globals);
            s.apps.putAll(apps); s.defaults.putAll(defaults); s.migration = migration; return s;
        }
        Row global(int user) { return globals.containsKey(user) ? globals.get(user) : globals.get(0); }
        GpuRange range(int user, String mode) {
            GpuRange result = defaults.get(key(user, mode)); return result == null ? factory(mode, false) : result;
        }
        Row resolved(int user, String pkg) {
            Row app = apps.get(key(user, pkg)); if (app != null) return app;
            Row global = global(user); GpuRange range = range(user, global.uperfMode);
            return new Row(global.refreshHz, global.uperfMode, range.minMHz, range.maxMHz);
        }
        byte[] bytes() {
            List<Object> inventory = new ArrayList<>(), global = new ArrayList<>(), rows = new ArrayList<>(), ranges = new ArrayList<>();
            for (Map.Entry<Integer,Long> e : users.entrySet()) inventory.add(map("userId", e.getKey(), "serial", e.getValue()));
            for (Map.Entry<Integer,Row> e : globals.entrySet()) global.add(map("userId", e.getKey(), "value", e.getValue().json()));
            for (Map.Entry<String,Row> e : apps.entrySet()) rows.add(map("key", e.getKey(), "value", e.getValue().json()));
            for (Map.Entry<String,GpuRange> e : defaults.entrySet()) ranges.add(map("key", e.getKey(), "min", e.getValue().minMHz, "max", e.getValue().maxMHz));
            byte[] result = PolicyJson.bytes(map("schema", 2, "generation", generation, "users", inventory, "globals", global, "apps", rows, "defaults", ranges, "migration", migration));
            require(result.length <= 98304, "policy size"); return result;
        }
        static State parse(byte[] bytes) throws Exception {
            Map<String,Object> root = object(PolicyJson.parse(bytes)); keys(root, "schema", "generation", "users", "globals", "apps", "defaults", "migration");
            require(integer(root.get("schema")) == 2, "policy schema"); State s = new State();
            s.generation = integer(root.get("generation")); require(s.generation > 0, "policy generation"); s.migration = string(root.get("migration")); require(s.migration.matches("[0-9a-f-]{36}"), "migration identity");
            for (Object item : array(root.get("users"))) {
                Map<String,Object> r = object(item); keys(r, "userId", "serial"); int user = user(r.get("userId")); long serial = integer(r.get("serial"));
                require(serial >= 0 && !s.users.containsKey(user) && !s.users.containsValue(serial), "user serial conflict"); s.users.put(user, serial);
            }
            for (Object item : array(root.get("globals"))) {
                Map<String,Object> r = object(item); keys(r, "userId", "value"); int user = user(r.get("userId"));
                require(s.users.containsKey(user) && !s.globals.containsKey(user), "global duplicate/unknown user"); s.globals.put(user, Row.from(r.get("value")));
            }
            require(s.globals.containsKey(0) && s.users.containsKey(0), "primary user missing");
            for (Object item : array(root.get("apps"))) {
                Map<String,Object> r = object(item); keys(r, "key", "value"); String key = string(r.get("key")); splitKey(key, false);
                require(!s.apps.containsKey(key), "app duplicate"); s.apps.put(key, Row.from(r.get("value")));
            }
            for (Object item : array(root.get("defaults"))) {
                Map<String,Object> r = object(item); keys(r, "key", "min", "max"); String key = string(r.get("key")); splitKey(key, true);
                require(!s.defaults.containsKey(key), "default duplicate"); s.defaults.put(key, new GpuRange(intValue(r.get("min")), intValue(r.get("max"))));
            }
            return s;
        }
    }
    static int user(Object value) { long id = integer(value); require(id >= 0 && id <= 21474, "userId"); return (int) id; }
    static boolean mode(String value) { return Arrays.asList(MODES).contains(value); }
    static String key(int user, String pkg) { return user + ":" + pkg; }
    static int splitKey(String key, boolean range) {
        String[] parts = key.split(":", -1); require(parts.length == 2 && parts[0].matches("0|[1-9][0-9]{0,4}"), "policy key");
        int user = user(Long.parseLong(parts[0])); require(range ? mode(parts[1]) : packageName(parts[1]), "policy target"); return user;
    }
    static boolean packageName(String pkg) { return pkg != null && pkg.length() <= 255 && pkg.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"); }
    static GpuRange factory(String mode, boolean legacy) {
        require(mode(mode), "GPU mode");
        switch (mode) {
            case "powersave": return new GpuRange(231, legacy ? 422 : 366);
            case "balance": return new GpuRange(231, legacy ? 629 : 578);
            case "performance": return new GpuRange(legacy ? 231 : 422, 903);
            default: return new GpuRange(629, 903);
        }
    }
    static <T> void unique(Map<String,T> map, String key, T value, String encoded) {
        // Duplicate rows, including identical ones, are ambiguous source syntax and rejected.
        require(!map.containsKey(key), "duplicate legacy " + encoded); map.put(key, value);
    }
    static final class Migration {
        final State state; final byte[] receipt;
        Migration(State s, byte[] r) { state = s; receipt = r; }
    }
    static Migration migrate(byte[] profiles, byte[] saved, byte[] perapp, String settingsMode,
            String settingsRules, Map<Integer,Long> users, Set<String> quarantined) throws Exception {
        String globalMode = utf8(saved).trim(); require(mode(globalMode), "legacy saved Uperf");
        require(globalMode.equals(settingsMode), "saved Uperf/Settings disagreement");
        State s = new State(); s.generation = 1; s.users.putAll(users); require(users.containsKey(0), "legacy user inventory");
        Map<String,Integer> refresh = new TreeMap<>(); Map<String,GpuRange> gpu = new TreeMap<>();
        Map<String,String> uperf = new TreeMap<>(); List<Object> quarantine = new ArrayList<>(); Set<Integer> rawDefaults = new HashSet<>();
        boolean version = profiles.length == 0, sentinel = false;
        for (String raw : utf8(profiles).split("\\r?\\n")) {
            String line = raw.trim(); if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.equals("version=1")) { require(!version, "legacy version duplicate"); version = true; continue; }
            String[] p = line.split("\\|", -1); require(p.length >= 3, "legacy row syntax"); int u = user(Long.parseLong(p[1]));
            if (p[0].equals("monitor")) { require(p.length == 3 && packageName(p[2]), "legacy monitor row"); quarantine.add(raw); continue; }
            if (p[0].equals("default")) {
                require(p.length == 5 && rawDefaults.add(u), "legacy default row");
                new Row(Integer.parseInt(p[2]), globalMode, 231, 903);
                require(p[3].equals("0") && p[4].equals("DISPLAY_ONLY"), "legacy unsupported default");
                quarantine.add(map("rawDefault", raw, "effectiveRefreshHz", 120)); continue;
            }
            String k = key(u, p[2]);
            if (p[0].equals("gpuGlobal")) {
                require(p.length == 5 && mode(p[2]), "legacy GPU default");
                unique(s.defaults, k, new GpuRange(Integer.parseInt(p[3]), Integer.parseInt(p[4])), raw); continue;
            }
            require(packageName(p[2]) || p[2].equals("android"), "legacy package");
            if (p[0].equals("pkg")) {
                require(p.length == 6 && p[4].equals("0") && p[5].equals("DISPLAY_ONLY"), "legacy refresh row");
                int hz = Integer.parseInt(p[3]); new Row(hz, globalMode, 231, 903); unique(refresh, k, hz, raw);
            } else {
                require(p[0].equals("gpu") && p.length == 5, "unknown legacy row"); unique(gpu, k, new GpuRange(Integer.parseInt(p[3]), Integer.parseInt(p[4])), raw);
            }
            if (quarantined.contains(p[2])) quarantine.add(raw);
        }
        require(version, "legacy version missing");
        for (String raw : utf8(perapp).split("\\r?\\n")) {
            String line = raw.trim(); if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\\s+"); require(p.length == 2 && mode(p[1]), "legacy Uperf row");
            if (p[0].equals("-")) { require(!sentinel && p[1].equals("powersave"), "legacy screen sentinel"); sentinel = true; quarantine.add(raw); continue; }
            require(packageName(p[0]) || p[0].equals("android"), "legacy Uperf package"); unique(uperf, p[0], p[1], raw);
        }
        Map<String,String> projected = new TreeMap<>();
        for (String raw : settingsRules.split("\\r?\\n")) {
            if (raw.trim().isEmpty()) continue; String[] p = raw.split("\\|", -1);
            require(p.length == 2 && mode(p[1]), "legacy Settings rule"); unique(projected, p[0], p[1], raw);
        }
        require(projected.equals(uperf), "Uperf rules/Settings disagreement");
        Set<String> all = new java.util.TreeSet<>(); all.addAll(refresh.keySet()); all.addAll(gpu.keySet());
        for (String pkg : uperf.keySet()) for (int user : users.keySet()) all.add(key(user, pkg));
        for (String k : all) {
            int colon = k.indexOf(':'); int user = Integer.parseInt(k.substring(0, colon)); String pkg = k.substring(colon + 1);
            if (quarantined.contains(pkg)) { quarantine.add(k); continue; }
            String mode = uperf.containsKey(pkg) ? uperf.get(pkg) : globalMode;
            GpuRange range = gpu.get(k);
            if (range == null) range = s.defaults.get(key(user, mode));
            if (range == null) range = factory(mode, true); // Snapshot OLD fallback before filling NEW defaults.
            s.apps.put(k, new Row(refresh.containsKey(k) ? refresh.get(k) : 120, mode, range.minMHz, range.maxMHz));
        }
        for (int user : users.keySet()) {
            for (String mode : MODES) if (!s.defaults.containsKey(key(user, mode))) s.defaults.put(key(user, mode), factory(mode, false));
            GpuRange range = s.range(user, globalMode); s.globals.put(user, new Row(120, globalMode, range.minMHz, range.maxMHz));
        }
        s.migration = UUID.randomUUID().toString();
        Map<String,Object> receipt = map("schema", 1, "transactionId", s.migration, "oldSchema", 1, "newSchema", 2, "phase", "PREPARED",
                "profilesHash", hash(profiles), "savedHash", hash(saved), "perappHash", hash(perapp),
                "settingsMode", settingsMode, "settingsRules", settingsRules, "users", object(parse(s.bytes())).get("users"),
                "quarantined", quarantine, "oldFallback", "231-422,231-629,231-903,629-903", "newFactory", "231-366,231-578,422-903,629-903", "targetHash", hash(s.bytes()));
        return new Migration(s, PolicyJson.bytes(receipt));
    }
    static State reconcileUsers(State old, Map<Integer,Long> inventory) {
        State next = old.copy(); boolean changed = false;
        for (Map.Entry<Integer,Long> e : inventory.entrySet()) {
            require(!old.users.containsKey(e.getKey()) || old.users.get(e.getKey()).equals(e.getValue()), "reused user serial");
            if (old.users.containsKey(e.getKey())) continue;
            for (String k : old.apps.keySet()) require(!k.startsWith(e.getKey() + ":"), "unresolved legacy user mapping");
            for (String k : old.defaults.keySet()) require(!k.startsWith(e.getKey() + ":"), "unresolved legacy default mapping");
            require(!next.users.containsValue(e.getValue()) && e.getValue() >= 0, "reused user inventory");
            next.users.put(e.getKey(), e.getValue()); next.globals.put(e.getKey(), old.global(0));
            for (String mode : MODES) next.defaults.put(key(e.getKey(), mode), old.range(0, mode));
            changed = true; // Inherit Global once, never clone an old package-only row.
        }
        if (changed) next.generation = Math.addExact(old.generation, 1); return changed ? next : old;
    }
    static State change(State old, long expected, int user, String pkg, String action, int value,
            String mode, int min, int max, boolean globalScope) {
        require(old.generation == expected, "STALE_POLICY_GENERATION"); require(old.users.containsKey(user), "unknown user");
        require(globalScope || packageName(pkg), "policy target"); State next = old.copy();
        Row r = globalScope ? old.global(user) : old.resolved(user, pkg);
        if (action.equals("delete")) { require(!globalScope, "cannot delete global"); next.apps.remove(key(user, pkg)); }
        else if (action.equals("defaultGpu")) {
            require(globalScope && mode(mode), "GPU default scope"); next.defaults.put(key(user, mode), new GpuRange(min, max));
            if (r.uperfMode.equals(mode)) next.globals.put(user, new Row(r.refreshHz, r.uperfMode, min, max));
        }
        else {
            if (action.equals("refresh")) r = new Row(value, r.uperfMode, r.gpuMinMHz, r.gpuMaxMHz);
            else if (action.equals("mode")) { GpuRange range = next.range(user, mode); r = new Row(r.refreshHz, mode, range.minMHz, range.maxMHz); }
            else if (action.equals("gpuDefault")) {
                require(!globalScope, "GPU reset scope"); GpuRange range = next.range(user, r.uperfMode);
                r = new Row(r.refreshHz, r.uperfMode, range.minMHz, range.maxMHz);
            } else { require(action.equals("gpu") && !globalScope, "policy action"); r = new Row(r.refreshHz, r.uperfMode, min, max); }
            if (globalScope) next.globals.put(user, r); else next.apps.put(key(user, pkg), r);
        }
        next.generation = Math.addExact(old.generation, 1); return next;
    }
    static boolean actionScope(long expectedScene, String expectedPackage, long currentScene, String currentPackage,
            String target, String scope, String home, String action, boolean interactive) {
        require(expectedScene == currentScene && expectedPackage.equals(currentPackage), "STALE_BUSINESS_SCENE");
        require(scope.equals("APP") || scope.equals("GLOBAL") || scope.equals("FOREGROUND"), "policy scope");
        if (scope.equals("FOREGROUND")) require(target.equals(currentPackage) && interactive && !target.isEmpty(), "STALE_FOREGROUND_TARGET");
        if (!home.isEmpty() && target.equals(home)) require(action.equals("refresh") || action.equals("mode"), "HOME global action");
        return scope.equals("GLOBAL") || (!home.isEmpty() && target.equals(home));
    }
    final Storage disk;
    volatile State current;
    volatile boolean recoveryRequired;
    AppPolicyStore(Storage storage) throws Exception { disk = storage; byte[] bytes = disk.read(ACTIVE); current = bytes.length == 0 ? null : State.parse(bytes); }
    private void immutable(String name, byte[] data) throws Exception {
        byte[] old = disk.read(name); require(old.length == 0 || Arrays.equals(old, data), "immutable policy evidence conflict"); if (old.length == 0) disk.write(name, data);
    }
    synchronized void migrate(Migration migration, byte[] profiles, byte[] saved, byte[] perapp, Owner owner) throws Exception {
        if (current != null) { recover(owner); return; }
        immutable("legacy-profiles.prop", profiles); immutable("legacy-saved.txt", saved); immutable("legacy-perapp.txt", perapp);
        byte[] prepared = disk.read("policy-migration-target.json");
        if (prepared.length != 0) migration.state.migration = State.parse(prepared).migration;
        Map<String,Object> receipt = object(parse(migration.receipt));
        receipt.put("transactionId", migration.state.migration);
        receipt.put("targetHash", hash(migration.state.bytes()));
        // Retry after any preparation write uses the same transaction and exact inputs,
        // including Settings, user serials and quarantine decisions. No fresh UUID conflict.
        immutable("policy-migration-target.json", migration.state.bytes());
        immutable("policy-migration.json", bytes(receipt));
        State target = State.parse(disk.read("policy-migration-target.json"));
        require(hash(target.bytes()).equals(object(parse(disk.read("policy-migration.json"))).get("targetHash")), "migration prepared digest");
        commit(target, owner);
    }
    synchronized void recover(Owner owner) throws Exception {
        byte[] raw = disk.read(JOURNAL); if (raw.length == 0) { recoveryRequired = false; return; }
        recoveryRequired = true;
        Map<String,Object> journal = object(parse(raw));
        keys(journal, "schema", "transaction", "phase", "previousFile", "targetFile", "previousHash", "targetHash");
        require(integer(journal.get("schema")) == 1, "policy journal schema");
        String phase = string(journal.get("phase"));
        if (phase.equals("APPLIED") || phase.equals("ABORTED")) {
            String expected = string(journal.get(phase.equals("APPLIED") ? "targetHash" : "previousHash"));
            require(hash(disk.read(ACTIVE)).equals(expected), "RECOVERY_REQUIRED_terminal_hash_mismatch");
            recoveryRequired = false; return;
        }
        require(phase.equals("PREPARED") || phase.equals("COMMIT_INTENT"), "policy journal phase");
        recoveryRequired = true;
        byte[] target = disk.read(string(journal.get("targetFile"))), previous = disk.read(string(journal.get("previousFile"))), active = disk.read(ACTIVE);
        require(hash(target).equals(string(journal.get("targetHash"))) && hash(previous).equals(string(journal.get("previousHash"))), "policy recovery hashes");
        if (Arrays.equals(active, previous)) {
            if (previous.length != 0) { State state = State.parse(previous); owner.prepare(state, string(journal.get("transaction"))); owner.apply(state, string(journal.get("transaction"))); current = state; }
            journal.put("phase", "ABORTED"); disk.write(JOURNAL, bytes(journal)); recoveryRequired = false; return;
        }
        require(Arrays.equals(active, target), "RECOVERY_REQUIRED_unknown_policy_commit");
        current = State.parse(target); owner.prepare(current, string(journal.get("transaction"))); owner.apply(current, string(journal.get("transaction")));
        journal.put("phase", "APPLIED"); disk.write(JOURNAL, bytes(journal)); recoveryRequired = false;
    }
    synchronized void commit(State next, Owner owner) throws Exception { commit(next, owner, true); }
    private void commit(State next, Owner owner, boolean rollbackAllowed) throws Exception {
        require(!recoveryRequired, "RECOVERY_REQUIRED"); State previousState = current;
        require(next.generation == (current == null ? 1 : current.generation + 1), "policy commit generation");
        byte[] previous = disk.read(ACTIVE), target = next.bytes(); State.parse(target);
        require(current == null ? previous.length == 0 : Arrays.equals(current.bytes(), previous), "policy disk CAS");
        String tx = UUID.randomUUID().toString(), prior = "policy-" + tx + "-previous.json", prepared = "policy-" + tx + "-target.json";
        immutable(prior, previous); immutable(prepared, target);
        Map<String,Object> journal = map("schema", 1, "transaction", tx, "phase", "PREPARED", "previousFile", prior,
                "targetFile", prepared, "previousHash", hash(previous), "targetHash", hash(target));
        disk.write(JOURNAL, bytes(journal)); recoveryRequired = true;
        try {
            owner.prepare(next, tx); journal.put("phase", "COMMIT_INTENT"); disk.write(JOURNAL, bytes(journal));
            disk.write(ACTIVE, target); current = next; owner.apply(next, tx);
            journal.put("phase", "APPLIED"); disk.write(JOURNAL, bytes(journal)); recoveryRequired = false;
        } catch (Exception failed) {
            // Inspect the rename result; never guess that an fsync error means no commit.
            byte[] actual = disk.read(ACTIVE);
            if (Arrays.equals(actual, previous)) {
                current = previousState; journal.put("phase", "ABORTED"); disk.write(JOURNAL, bytes(journal)); recoveryRequired = false;
            } else if (Arrays.equals(actual, target) && previousState != null && rollbackAllowed) {
                current = next; State rollback = previousState.copy(); rollback.generation = Math.addExact(next.generation, 1);
                recoveryRequired = false;
                try { commit(rollback, owner, false); } catch (Exception error) { recoveryRequired = true; failed.addSuppressed(error); }
            }
            throw failed;
        }
    }
}
