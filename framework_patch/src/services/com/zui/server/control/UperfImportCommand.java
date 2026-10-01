package com.zui.server.control;

import android.os.SystemClock;
import android.os.SystemProperties;
import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import static com.zui.server.control.PolicyJson.*;

/** One-shot upload/selection commands on the existing root command plane. */
final class UperfImportCommand {
    static final String SYSTEM = "/system/etc/zui_control/";
    static final class Fileset implements AppPolicyStore.Storage {
        final PolicyCommand.Disk root;
        Fileset() throws Exception { root = new PolicyCommand.Disk(new File(PolicyCommand.UPERF)); }
        private PolicyCommand.Disk parent(String name) throws Exception {
            if (!name.startsWith("imports/")) return root;
            String[] parts = name.split("/", -1);
            require(parts.length == 3 && parts[1].matches("[0-9a-f]{64}")
                    && Arrays.asList("manifest.json", "config.json", "evidence.json").contains(parts[2]), "import storage path");
            return new PolicyCommand.Disk(new File(root.directory, "imports/" + parts[1]));
        }
        public byte[] read(String name) throws Exception { return parent(name).read(name.substring(name.lastIndexOf('/') + 1)); }
        public void write(String name, byte[] data) throws Exception { parent(name).write(name.substring(name.lastIndexOf('/') + 1), data); }
        public String[] names()throws Exception{return root.names();}
        public void remove(String name)throws Exception{root.remove(name);}
    }
    private static byte[] fixed(String path, int max) throws Exception {
        File file = new File(path); require(file.getCanonicalFile().equals(file.getAbsoluteFile()) && file.length() <= max, "immutable source path/size");
        byte[] result = Files.readAllBytes(file.toPath()); require(result.length > 0 && result.length <= max, "immutable source read bound"); return result;
    }
    private static UperfConfigStore store(Fileset files) throws Exception {
        byte[] profile = fixed(SYSTEM + "uperf-compatibility.json", 131072);
        String binaryHash = string(object(parse(profile)).get("binaryHash"));
        require(binaryHash.matches("[0-9a-f]{64}"), "ROM binary identity");
        // The verified ROM pins the binary identity. performanced compares the
        // actual executable before every launch; shell cannot read its exec type.
        return new UperfConfigStore(files, fixed(SYSTEM + "uperf-sm8650.json", 131072),
                binaryHash, profile);
    }
    private static String boot() throws Exception { return new String(fixed("/proc/sys/kernel/random/boot_id", 128), java.nio.charset.StandardCharsets.US_ASCII).trim(); }
    private static Map<String,Object> upload(Fileset files, String tx) throws Exception {
        require(tx.matches("[0-9a-f]{24}"), "upload transaction");
        Map<String,Object> meta = object(parse(files.read("uperf-upload-" + tx + ".json")));
        require(meta.get("boot").equals(boot()) && SystemClock.elapsedRealtime() >= integer(meta.get("time"))
                && SystemClock.elapsedRealtime() - integer(meta.get("time")) <= 600000, "expired Uperf upload"); return meta;
    }
    static String run(String action, String argument) throws Exception {
        Fileset files = new Fileset(); UperfConfigStore store = store(files); long restartGeneration = -1;
        // init's finite shell-domain prepare owns selection completion. The native
        // supervisor publishes readiness but must never launch ART from performanced.
        boolean observing = action.equals("uperf-observe-startup");
        long observedGeneration = observing ? integer(store.selection().get("generation")) : -1;
        boolean startupReady = observing && awaitStartup(files);
        File lockFile = new File(files.root.directory, "import-manager.lock");
        if (!lockFile.exists()) files.root.write("import-manager.lock", new byte[0]);
        files.root.read("import-manager.lock"); // Validate owner/type/link count before locking.
        try (FileChannel channel = FileChannel.open(lockFile.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                FileLock lock = channel.tryLock()) {
            require(lock != null, "Uperf import busy");
            if (action.equals("uperf-startup")) {
                // Materialization is a projection of validated selection; old imports survive prepare/reboot.
                byte[] runtime = store.startup(); files.root.write("uperf.json", runtime);
                files.root.write(".validated_runtime.sha256", (hash(runtime) + "  " + PolicyCommand.UPERF + "/uperf.json\n"
                        + store.binaryHash + "  /system/bin/uperf\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                return "selection=" + encode(store.selection());
            }
            if (observing) {
                Map<String,Object> selection = store.selection();
                require(integer(selection.get("generation")) == observedGeneration, "startup selection changed");
                if (selection.get("state").equals("PENDING")) {
                    byte[] runtime = files.root.read("uperf.json"); require(hash(runtime).equals(selection.get("hash")), "startup selection generation");
                    store.complete(observedGeneration, startupReady);
                    if (!startupReady) files.root.write("uperf.json", store.startup());
                }
                require(startupReady, "Uperf startup not ready; last-good/factory retained");
                return "selection=" + encode(store.selection());
            }
            if (action.equals("ui_state")) return "selection=" + encode(store.selection());
            String[] p = argument.split(":", -1); String tx = p[0];
            if (action.equals("ui_begin")) {
                require(p.length == 4 && tx.matches("[0-9a-f]{24}") && p[2].matches("[0-9a-f]{64}"), "Uperf begin");
                int size = Integer.parseInt(p[1]); long generation = Long.parseLong(p[3]); require(size > 0 && size <= UperfConfigStore.LIMIT, "upload size");
                require(integer(store.selection().get("generation")) == generation, "upload base generation");
                java.util.Set<String> protectedIds=new java.util.HashSet<>();Map<String,Object> selected=store.selection();
                for(String name:files.names())if(name.matches("uperf-upload-[0-9a-f]{24}\\.json")){
                    Map<String,Object> meta=object(parse(files.read(name)));
                    // Selection can commit before selectedGeneration is persisted.
                    // Protect that crash window, including startup completion/rollback.
                    long expected=integer(meta.get("generation")),active=integer(selected.get("generation"));
                    if(expected+1==active||(expected+2==active&&selected.get("state").equals("ROLLED_BACK")))
                        protectedIds.add(name.substring(13,37));
                    if(meta.containsKey("selectedGeneration")){
                        long g=integer(meta.get("selectedGeneration")),current=integer(selected.get("generation"));
                        if(g==current||(g+1==current&&selected.get("state").equals("ROLLED_BACK")))protectedIds.add(name.substring(13,37));
                    }
                }
                AppPolicyStore.Uploads.prune(files,"uperf-upload-",".bin",boot(),SystemClock.elapsedRealtime(),protectedIds,UperfConfigStore.LIMIT);
                byte[] prior = files.read("uperf-upload-" + tx + ".json");
                if (prior.length != 0) {
                    Map<String,Object> old = upload(files, tx); require(integer(old.get("size")) == size && old.get("hash").equals(p[2]) && integer(old.get("generation")) == generation, "upload replay conflict");
                    return "upload=resumed";
                }
                AppPolicyStore.Uploads.quota(files,"uperf-upload-",".bin",UperfConfigStore.LIMIT);
                Map<String,Object> meta=map("size", size, "hash", p[2], "generation", generation, "boot", boot(), "time", SystemClock.elapsedRealtime());
                AppPolicyStore.Uploads.begin(meta,boot(),SystemClock.elapsedRealtime());
                files.write("uperf-upload-" + tx + ".json",bytes(meta));files.write("uperf-upload-" + tx + ".bin", new byte[0]);
                return "upload=begun";
            }
            if (action.equals("ui_chunk")) {
                require(p.length == 3, "Uperf chunk"); Map<String,Object> meta = upload(files, tx); int offset = Integer.parseInt(p[1]);
                byte[] next = Base64.getDecoder().decode(p[2]), previous = files.read("uperf-upload-" + tx + ".bin");
                require(next.length > 0 && next.length <= 8192 && offset >= 0 && offset <= previous.length && (long) offset + next.length <= integer(meta.get("size")), "upload chunk bound");
                if (offset < previous.length) { require(offset + next.length <= previous.length && Arrays.equals(Arrays.copyOfRange(previous, offset, offset + next.length), next), "upload replay"); return "chunk=replayed"; }
                byte[] data = Arrays.copyOf(previous, previous.length + next.length); System.arraycopy(next, 0, data, offset, next.length);
                files.write("uperf-upload-" + tx + ".bin", data); return "received=" + data.length;
            }
            if (action.equals("ui_commit")) {
                require(p.length == 2 && p[1].isEmpty(), "Uperf commit"); Map<String,Object> meta = upload(files, tx);
                byte[] archive = files.read("uperf-upload-" + tx + ".bin"); require(archive.length == integer(meta.get("size")) && hash(archive).equals(meta.get("hash")), "upload integrity");
                if (meta.containsKey("selectedGeneration")) restartGeneration = integer(meta.get("selectedGeneration"));
                else {
                    byte[] beforeSelection=files.read(UperfConfigStore.ACTIVE);
                    try{restartGeneration = store.resumeStage(archive, integer(meta.get("generation")));}
                    catch(IllegalArgumentException rejected){
                        Map<String,Object> unchanged=store.selection();
                        if(Arrays.equals(beforeSelection,files.read(UperfConfigStore.ACTIVE))
                                &&integer(unchanged.get("generation"))==integer(meta.get("generation"))&&!unchanged.get("state").equals("PENDING")){
                            meta.put("transferState","REJECTED");files.write("uperf-upload-"+tx+".json",bytes(meta));
                        }
                        throw rejected;
                    }
                    meta.put("selectedGeneration", restartGeneration);
                    files.write("uperf-upload-" + tx + ".json", bytes(meta));
                }
            } else if (action.equals("ui_reset")) {
                long expected = Long.parseLong(argument); store.reset(expected); restartGeneration = expected + 1;
            } else throw new IllegalArgumentException("Uperf command");
        }
        // Do not hold the selection lock across init's prepare: it validates the same active generation.
        Map<String,Object> selected = store.selection();
        require(integer(selected.get("generation")) == restartGeneration, "selection changed before restart");
        if (selected.get("state").equals("ACCEPTED")) return "generation=" + restartGeneration + ";state=ACCEPTED";
        boolean ready = restartReady(files);
        try (FileChannel channel = FileChannel.open(lockFile.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                FileLock lock = channel.tryLock()) {
            require(lock != null, "Uperf completion busy");
            Map<String,Object> actual = store.selection();
            if (integer(actual.get("generation")) == restartGeneration) store.complete(restartGeneration, ready);
            else require(actual.get("state").equals("ROLLED_BACK") && integer(actual.get("generation")) == restartGeneration + 1, "indeterminate selection: query state");
            ready = ready && store.selection().get("state").equals("ACCEPTED");
        }
        if (!ready) { restartReady(files); throw new IllegalStateException("import rejected; last-good/factory selected; query state"); }
        return "generation=" + restartGeneration + ";state=ACCEPTED";
    }
    private static boolean awaitStartup(Fileset files) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 35000;
        do {
            Thread.sleep(100);
            // prepare cleared the marker before asking init to start this generation.
            if (SystemProperties.get("zui_control.scheduler", "").equals("restarted")) {
                if (SystemProperties.get("sys.zui_control.uperf_fail_safe", "0").equals("0")
                        && files.root.read(".service_ready_uptime").length != 0
                        && SystemProperties.get("init.svc.zui_uperf", "").equals("running")) return true;
            }
        } while (SystemClock.elapsedRealtime() < deadline);
        return false;
    }
    private static boolean restartReady(Fileset files) throws Exception {
        byte[] before = files.root.read(".service_ready_uptime");
        SystemProperties.set("zui_control.scheduler", "restart"); long deadline = SystemClock.elapsedRealtime() + 35000;
        do {
            Thread.sleep(100);
            byte[] ready = files.root.read(".service_ready_uptime");
            if (ready.length != 0 && !Arrays.equals(before, ready) && SystemProperties.get("zui_control.scheduler", "").equals("restarted")
                    && SystemProperties.get("init.svc.zui_uperf", "").equals("running")) return true;
            if (SystemProperties.get("sys.zui_control.uperf_fail_safe", "0").equals("1")) return false;
        } while (SystemClock.elapsedRealtime() < deadline);
        return false;
    }
}
