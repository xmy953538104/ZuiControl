package com.zui.server.control;

import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.util.zip.*;
import static com.zui.server.control.PolicyJson.*;

public final class UperfConfigFixture {
    static int checks;
    static void check(boolean b, String why) { if (!b) throw new AssertionError(why); checks++; }
    interface Work { void run() throws Exception; }
    static void rejects(Work work) throws Exception { try { work.run(); } catch (Exception expected) { checks++; return; } throw new AssertionError("invalid import accepted"); }
    static final class Disk implements AppPolicyStore.Storage {
        final Map<String,byte[]> data = new HashMap<>();
        public byte[] read(String name) { return data.getOrDefault(name, new byte[0]).clone(); }
        public void write(String name, byte[] bytes) { data.put(name, bytes.clone()); }
    }
    static byte[] factory, evidence = bytes(map("qualification", "TEST_ONLY_metadata_profile"));
    static Map<String,Object> trust, profile;
    static Map<String,byte[]> entries(byte[] config, byte[] base, String invalidKey, Object invalidValue) throws Exception {
        Map<String,Object> workflow = map("repository", "test/qualified-workflow", "commit", String.join("", Collections.nCopies(40, "a")),
                "runId", "fixture", "artifactHash", hash(config), "oldManifestHash", hash(base), "newManifestHash", hash(config),
                "semanticDiffHash", hash(bytes(map("before", hash(base), "after", hash(config)))), "qualificationEvidenceHash", hash(evidence));
        Map<String,Object> manifest = map("schemaVersion", 1, "kind", "zui.uperf.config-only", "sourceVersion", "fixture-1", "targetSoC", "sm8650",
                "payloadHash", hash(config), "acceptedBaseHash", hash(base), "factoryHash", hash(factory), "expectedUperfBinaryHash", UperfConfigStore.BINARY_HASH,
                "compatibilityClass", "ALLOWED_CONFIG_ONLY", "compatibilityProfileId", "fixture", "compatibilityProfileHash", hash(bytes(profile)),
                "qualifiedConfigPayload", "config.json", "workflow", workflow);
        if (invalidKey != null) manifest.put(invalidKey, invalidValue);
        String digest = hash(bytes(manifest)); object(trust.get("qualifications")).put(hash(config), digest);
        workflow.put("attestation", "rom-qualified:" + digest);
        Map<String,byte[]> result = new HashMap<>(); result.put("manifest.json", bytes(manifest)); result.put("config.json", config); result.put("evidence.json", evidence); return result;
    }
    static byte[] zip(Map<String,byte[]> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String name : Arrays.asList("manifest.json", "config.json", "evidence.json")) {
                byte[] data = entries.get(name); CRC32 crc = new CRC32(); crc.update(data);
                ZipEntry entry = new ZipEntry(name); entry.setMethod(ZipEntry.STORED); entry.setSize(data.length); entry.setCrc(crc.getValue()); entry.setTime(315532800000L);
                zip.putNextEntry(entry); zip.write(data); zip.closeEntry();
            }
        }
        byte[] data = out.toByteArray();
        for (int i = 0; i < data.length - 46; i++) if ((data[i] & 255) == 0x50 && (data[i+1]&255)==0x4b && (data[i+2]&255)==1 && (data[i+3]&255)==2) {
            data[i+5] = 3; int attributes = 0100600 << 16;
            for (int b = 0; b < 4; b++) data[i+38+b]=(byte)(attributes >>> (8*b));
        }
        return data;
    }
    static byte[] named(String name) throws Exception { Map<String,Object> config = object(parse(factory)); object(config.get("meta")).put("name", name); return bytes(config); }
    static UperfConfigStore store(Disk disk) throws Exception { return new UperfConfigStore(disk, factory, UperfConfigStore.BINARY_HASH, bytes(trust)); }
    static boolean launchSelected(UperfConfigStore store, String binary, String dummy) throws Exception {
        Path root = Files.createTempDirectory("uperf-selection-process-");
        Path config = root.resolve("uperf.json"), log = root.resolve("uperf.log"), ready = root.resolve("ready");
        Files.write(config, store.startup());
        ProcessBuilder builder = new ProcessBuilder(binary, config.toString(), log.toString(), ready.toString());
        builder.environment().put("ZUI_UPERF_TEST_BINARY", dummy);
        builder.environment().put("ZUI_UPERF_TEST_TIMEOUT_MS", "2000");
        builder.environment().put("ZUI_UPERF_TEST_CADENCE_MS", "20");
        builder.redirectError(root.resolve("stderr").toFile());
        Process process = builder.start();
        try {
            check(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS), "finite native child exit");
            check(process.exitValue() == 1, "real supervisor observes descendant termination");
            boolean valid = Files.isRegularFile(ready);
            store.complete(integer(store.selection().get("generation")), valid);
            return valid;
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
            Path pid = root.resolve("state/daemon.pid");
            if (Files.exists(pid)) {
                long id = Long.parseLong(new String(Files.readAllBytes(pid)).trim());
                long deadline = System.nanoTime() + 3_000_000_000L;
                while (Files.exists(Paths.get("/proc/" + id)) && System.nanoTime() < deadline) Thread.sleep(10);
                check(!Files.exists(Paths.get("/proc/" + id)), "child reaped; no surviving fixture daemon");
            }
        }
    }
    static void nativeLifecycle(String binary, String dummy) throws Exception {
        Disk disk = new Disk();
        byte[] good = named("ready_short"), bad = named("native_failed");
        byte[] goodZip = zip(entries(good, factory, null, null));
        byte[] badZip = zip(entries(bad, good, null, null));
        UperfConfigStore store = store(disk);
        store.stage(goodZip, 0);
        check(launchSelected(store, binary, dummy), "accepted native readiness");
        check(store.selection().get("state").equals("ACCEPTED"), "last-good established");
        store.stage(badZip, 1);
        check(!launchSelected(store, binary, dummy), "actual failed process has no ready ACK");
        check(Arrays.equals(store.startup(), good) && store.selection().get("state").equals("ROLLED_BACK"), "native failure rolls back production selection");
        check(launchSelected(store, binary, dummy), "restart from last-good succeeds");
        check(integer(store.selection().get("generation")) == 3, "single rollback generation");
        check(disk.data.containsKey("imports/" + hash(bad) + "/config.json"), "failed config evidence retained");
        System.out.println("UPERF_NATIVE_PROCESS_SELECTION_ROLLBACK_PASS checks=" + checks);
    }
    public static void main(String[] args) throws Exception {
        factory = new String(Files.readAllBytes(Paths.get(args[0])), java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        check(hash(factory).equals(UperfConfigStore.FACTORY_HASH), "qualified immutable factory");
        profile = map("schema", 1, "binaryHash", UperfConfigStore.BINARY_HASH, "factoryHash", hash(factory), "targetSoC", "sm8650",
                "fields", map("/meta/name", map("type", "string", "maxUtf8Bytes", 128)), "qualificationEvidenceHash", hash(evidence));
        trust = map("schema", 1, "targetSoC", "sm8650", "factoryHash", hash(factory), "binaryHash", UperfConfigStore.BINARY_HASH, "profiles", map("fixture", profile), "qualifications", map());
        if (args.length == 3) { nativeLifecycle(args[1], args[2]); return; }
        Disk disk = new Disk(); check(Arrays.equals(factory, store(disk).startup()), "factory selection");
        byte[] first = named("qualified-one"); Map<String,byte[]> firstEntries = entries(first, factory, null, null);
        byte[] firstZip = zip(firstEntries); check(UperfConfigStore.unpack(firstZip).size() == 3, "strict archive");
        UperfConfigStore one = store(disk); long generation = one.stage(firstZip, 0); check(generation == 1 && one.selection().get("state").equals("PENDING"), "stage pending until readiness");
        check(Arrays.equals(first, one.startup()), "startup selected overlay"); one.complete(1, true);
        check(Arrays.equals(first, store(disk).startup()), "overlay survives restart");
        byte[] snapshot = disk.read(UperfConfigStore.ACTIVE);
        for (Map.Entry<String,Object> invalid : map("schemaVersion", 9, "targetSoC", "sm8550", "expectedUperfBinaryHash", hash(new byte[0]),
                "acceptedBaseHash", hash(factory), "factoryHash", hash(new byte[0]), "compatibilityProfileHash", hash(new byte[0]), "unknown", true,
                "compatibilityClass", "ROM_REQUIRED", "payloadHash", hash(new byte[0])).entrySet()) {
            byte[] second = named("invalid-" + invalid.getKey()); byte[] bad = zip(entries(second, first, invalid.getKey(), invalid.getValue()));
            rejects(() -> store(disk).stage(bad, 1)); check(Arrays.equals(snapshot, disk.read(UperfConfigStore.ACTIVE)), "whole invalid package retains active");
        }
        Map<String,Object> forbidden = object(parse(first)); object(object(forbidden.get("modules")).get("sched")).put("enable", true);
        byte[] badOwnership = zip(entries(bytes(forbidden), first, null, null)); rejects(() -> store(disk).stage(badOwnership, 1));
        Map<String,Object> unknown = object(parse(first)); unknown.put("gpu", map("enable", true));
        byte[] unknownZip = zip(entries(bytes(unknown), first, null, null)); rejects(() -> store(disk).stage(unknownZip, 1));
        Map<String,Object> unqualified = object(parse(first)); object(object(unqualified.get("modules")).get("log")).put("level", "debug");
        byte[] unknownBehavior = zip(entries(bytes(unqualified), first, null, null)); rejects(() -> store(disk).stage(unknownBehavior, 1));
        byte[] second = named("qualified-two"); byte[] secondZip = zip(entries(second, first, null, null)); UperfConfigStore two = store(disk);
        check(two.stage(secondZip, 1) == 2, "chained exact base"); two.complete(2, false);
        check(Arrays.equals(first, two.startup()) && integer(two.selection().get("generation")) == 3, "failed launch rolls back once to last-good");
        rejects(() -> two.stage(secondZip, 1)); rejects(() -> two.stage(secondZip, 3));
        check(disk.data.containsKey("imports/" + hash(second) + "/config.json"), "failed artifact retained");
        two.reset(3); two.complete(4, true); check(Arrays.equals(factory, two.startup()), "logical factory reset");
        check(disk.data.containsKey("imports/" + hash(first) + "/config.json"), "reset retains imported evidence");
        Map<String,Object> empty = map("schema", 1, "targetSoC", "sm8650", "factoryHash", hash(factory), "binaryHash", UperfConfigStore.BINARY_HASH, "profiles", map(), "qualifications", map());
        UperfConfigStore productionClosed = new UperfConfigStore(new Disk(), factory, UperfConfigStore.BINARY_HASH, bytes(empty));
        rejects(() -> productionClosed.stage(firstZip, 0));
        check(UperfConfigStore.classify("/modules/sfanalysis/enable").equals("FORBIDDEN"), "SF analyzer stays closed");
        check(UperfConfigStore.classify("/initials/cpu/latency").equals("REQUIRES_MANUAL_REVIEW"), "CPU not generic numeric allowlist");
        // Central and local identities must match before any parsed input is used.
        for (int field : new int[] {4, 6, 10, 14, 18, 22, 30}) {
            byte[] corrupt = firstZip.clone(); corrupt[field] ^= 1;
            rejects(() -> UperfConfigStore.unpack(corrupt));
        }
        Disk lostReply = new Disk(); UperfConfigStore lost = store(lostReply);
        check(lost.stage(firstZip, 0) == 1 && lost.resumeStage(firstZip, 0) == 1, "lost stage reply reconciles actual bytes without repeat commit");
        lost.complete(1, true); check(lost.resumeStage(firstZip, 0) == 1, "accepted stage reply replay");
        Disk corruptOverlay = new Disk(); corruptOverlay.data.putAll(lostReply.data);
        corruptOverlay.data.put("imports/" + hash(first) + "/config.json", bytes(map("corrupt", true)));
        check(Arrays.equals(factory, store(corruptOverlay).startup()), "corrupt selected payload falls back to factory");
        // Test-only qualification of the actual CPU:kHz wire shape; production registry stays empty.
        object(profile.get("fields")).put("/initials/sysfs/msmCpuMin", map("type", "cpu-khz-list", "unit", "kHz", "topology", "sm8650:0-1,2-4,5-6,7",
                "min", 0, "max", 300000, "values", Arrays.asList(0, 300000), "qualificationEvidenceHash", hash(evidence)));
        Map<String,Object> cpu = object(parse(factory));
        object(object(cpu.get("initials")).get("sysfs")).put("msmCpuMin", "0:300000 1:300000 2:0 3:0 4:0 5:0 6:0 7:0");
        byte[] cpuZip = zip(entries(bytes(cpu), factory, null, null)); UperfConfigStore cpuStore = store(new Disk());
        check(cpuStore.stage(cpuZip, 0) == 1, "qualified exact CPU mapping");
        object(object(cpu.get("initials")).get("sysfs")).put("msmCpuMin", "0:300001 1:300000 2:0 3:0 4:0 5:0 6:0 7:0");
        byte[] badCpu = zip(entries(bytes(cpu), factory, null, null)); rejects(() -> store(new Disk()).stage(badCpu, 0));
        object(object(profile.get("fields")).get("/initials/sysfs/msmCpuMin")).put("unit", "Hz");
        Map<String,Object> wrongUnit = object(parse(factory)); object(object(wrongUnit.get("initials")).get("sysfs")).put("msmCpuMin", "0:300000 1:0 2:0 3:0 4:0 5:0 6:0 7:0");
        byte[] unitZip = zip(entries(bytes(wrongUnit), factory, null, null)); rejects(() -> store(new Disk()).stage(unitZip, 0));
        rejects(() -> UperfConfigStore.cpuFrequencies("1:0 0:0 2:0 3:0 4:0 5:0 6:0 7:0"));
        System.out.println("UPERF_CONFIG_PRODUCTION_FIXTURE_PASS checks=" + checks);
    }
}
