package com.zui.server.control;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import static com.zui.server.control.PolicyJson.*;

/** Finite adapter invoked by the existing authenticated root command owner, never a service. */
public final class PolicyCommand {
    static final int TRANSACTION = 1010;
    static final String DESCRIPTOR = "android.zui.IZuiControl";
    static final String CALLBACK = "com.zui.server.control.IPolicyProjection";
    static final String UPERF = "/data/vendor/zui_control/uperf";
    static final class Disk implements AppPolicyStore.Storage {
        final File directory;
        Disk(File path) throws Exception {
            directory = path;
            require(path.isDirectory() || path.mkdirs(), "policy directory");
            require(path.getCanonicalFile().equals(path.getAbsoluteFile()), "policy directory symlink");
            require(Os.lstat(path.getPath()).st_uid == Os.geteuid(), "policy directory owner");
        }
        private File file(String name) throws Exception {
            require(name.matches("[a-zA-Z0-9_.-]{1,100}") && !name.equals(".") && !name.equals(".."), "policy file name");
            File file = new File(directory, name);
            for (String suffix : new String[] {"", ".bak", ".new"}) {
                File candidate = new File(directory, name + suffix);
                try {
                    StructStat st = Os.lstat(candidate.getPath());
                    require(OsConstants.S_ISREG(st.st_mode) && st.st_uid == Os.geteuid() && st.st_nlink == 1 && st.st_size >= 0 && st.st_size <= 262144, "policy file identity");
                } catch (android.system.ErrnoException e) { if (e.errno != OsConstants.ENOENT) throw e; }
            }
            return file;
        }
        public byte[] read(String name) throws Exception {
            File file = file(name); AtomicFile atomic = new AtomicFile(file);
            if (!file.exists() && !new File(directory, name + ".bak").exists()) return new byte[0];
            require(file.length() <= 262144, "policy file bound"); byte[] bytes = atomic.readFully(); require(bytes.length <= 262144, "policy read bound"); return bytes;
        }
        public void write(String name, byte[] bytes) throws Exception {
            require(bytes.length <= 262144, "policy write bound"); AtomicFile file = new AtomicFile(file(name)); FileOutputStream out = null;
            try { out = file.startWrite(); out.write(bytes); out.getFD().sync(); file.finishWrite(out); out = null;
                Os.chmod(new File(directory, name).getPath(), 0600);
                java.io.FileDescriptor dir = Os.open(directory.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0);
                try { require(OsConstants.S_ISDIR(Os.fstat(dir).st_mode), "policy sync directory"); Os.fsync(dir); } finally { Os.close(dir); }
                require(Arrays.equals(read(name), bytes), "policy durable readback");
            } catch (Exception e) { if (out != null) file.failWrite(out); throw e; }
        }
    }
    private static byte[] legacy(String name) throws Exception {
        File file = new File(UPERF, name); StructStat st = Os.lstat(file.getPath());
        require(OsConstants.S_ISREG(st.st_mode) && st.st_uid == 0 && st.st_nlink == 1 && st.st_size <= 65536, "legacy native identity");
        return Files.readAllBytes(file.toPath());
    }
    private static String callback(IBinder remote, String action, String argument) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try { data.writeInterfaceToken(CALLBACK); data.writeString(action); data.writeString(argument);
            require(remote.transact(1, data, reply, 0), "policy owner unavailable"); reply.readException(); return reply.readString();
        } finally { data.recycle(); reply.recycle(); }
    }
    static AppPolicyStore.Owner owner(IBinder remote, java.util.function.Consumer<AppPolicyStore.State> runtime) {
        return new AppPolicyStore.Owner() {
            public void prepare(AppPolicyStore.State next, String tx) throws Exception {
                require(callback(remote, "prepare", encode(map("transaction", tx, "policy", object(parse(next.bytes()))))).equals(hash(next.bytes())), "native prepare ACK");
            }
            public void apply(AppPolicyStore.State next, String tx) throws Exception {
                runtime.accept(next);
                require(callback(remote, "apply", encode(map("transaction", tx, "hash", hash(next.bytes()), "generation", next.generation))).equals(hash(next.bytes())), "native applied ACK");
            }
        };
    }
    static Map<String,Object> snapshot(IBinder remote) throws Exception {
        return object(parse(callback(remote, "legacy", "").getBytes(StandardCharsets.UTF_8)));
    }
    private static final class Projection extends Binder {
        final Disk disk;
        Projection() throws Exception { disk = new Disk(new File(UPERF, "policy-projection")); }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                require(Binder.getCallingUid() == 1000 && code == 1 && flags == 0, "system policy authority required");
                data.enforceInterface(CALLBACK); String action = data.readString(), text = data.readString(); require(data.dataAvail() == 0, "projection trailing data");
                String result;
                if (action.equals("legacy")) {
                    result = encode(map("saved", Base64.getEncoder().encodeToString(legacy("cur_powermode.txt")), "perapp", Base64.getEncoder().encodeToString(legacy("perapp_powermode.txt"))));
                } else {
                    Map<String,Object> request = object(parse(text.getBytes(StandardCharsets.UTF_8)));
                    String tx = string(request.get("transaction")); require(tx.matches("[0-9a-f-]{36}"), "projection transaction");
                    if (action.equals("prepare")) {
                        keys(request, "transaction", "policy"); byte[] policy = bytes(request.get("policy")); AppPolicyStore.State.parse(policy);
                        byte[] prior = disk.read(tx + ".json"); require(prior.length == 0 || Arrays.equals(prior, policy), "projection immutable stage");
                        if (prior.length == 0) disk.write(tx + ".json", policy);
                        result = hash(policy); // Nothing visible changes before authoritative config commit.
                    } else {
                        require(action.equals("apply"), "projection action"); keys(request, "transaction", "hash", "generation");
                        byte[] prepared = disk.read(tx + ".json"); AppPolicyStore.State state = AppPolicyStore.State.parse(prepared);
                        require(hash(prepared).equals(string(request.get("hash"))) && state.generation == integer(request.get("generation")), "projection identity");
                        // Native data is a tagged compatibility projection, never independently edited policy.
                        byte[] active = disk.read("active.json");
                        if (active.length != 0) {
                            AppPolicyStore.State before = AppPolicyStore.State.parse(active);
                            require(before.generation < state.generation || (before.generation == state.generation && Arrays.equals(active, prepared)), "projection generation CAS");
                        }
                        String desired = android.os.SystemProperties.get("sys.zui_control.uperf_mode", "");
                        require(AppPolicyStore.mode(desired), "Uperf desired runtime mode");
                        File mode = new File(UPERF, "effective_powermode.txt");
                        java.io.FileDescriptor fd = Os.open(mode.getPath(), OsConstants.O_WRONLY | OsConstants.O_NOFOLLOW, 0);
                        try { StructStat st = Os.fstat(fd);
                            require(OsConstants.S_ISREG(st.st_mode) && st.st_uid == 0 && st.st_nlink == 1, "Uperf runtime file");
                            Os.ftruncate(fd, 0);
                            byte[] value = (desired + "\n").getBytes(StandardCharsets.US_ASCII); int offset = 0;
                            while (offset < value.length) { int n = Os.write(fd, value, offset, value.length - offset); require(n > 0, "runtime mode write"); offset += n; }
                            Os.fsync(fd);
                        } finally { Os.close(fd); }
                        require(new String(Files.readAllBytes(mode.toPath()), StandardCharsets.US_ASCII).trim().equals(desired), "Uperf runtime file ACK");
                        disk.write("active.json", prepared); disk.write("applied.json", bytes(request)); result = hash(prepared);
                    }
                }
                reply.writeNoException(); reply.writeString(result);
            } catch (Exception e) { reply.writeException(e); }
            return true;
        }
    }
    public static void main(String[] args) {
        try {
            require(Os.geteuid() == 0 && args.length == 2, "root policy command arguments");
            if (args[0].startsWith("uperf-") || args[0].startsWith("ui_")) {
                System.out.println(UperfImportCommand.run(args[0], args[1])); System.exit(0); return;
            }
            IBinder remote = ServiceManager.getService("zui_control"); require(remote != null, "policy service unavailable");
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try { data.writeInterfaceToken(DESCRIPTOR); data.writeString(args[0]); data.writeString(args[1]); data.writeStrongBinder(new Projection());
                require(remote.transact(TRANSACTION, data, reply, 0), "policy service transaction"); reply.readException();
                String result = reply.readString(); System.out.println(result); require(result.startsWith("ok=1"), "policy transaction failed; query state");
            } finally { data.recycle(); reply.recycle(); }
        } catch (Exception e) { System.err.println("policy rejected: " + e.getClass().getSimpleName() + ":" + e.getMessage()); System.exit(1); }
        System.exit(0); // Binder callback pool must not leave a resident root process.
    }
}
