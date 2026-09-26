package com.zui.server.control;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import static com.zui.server.control.PolicyJson.*;

/** Config-only qualification and selection. No binary, task, GPU, thermal or sampling owner. */
final class UperfConfigStore {
    static final String FACTORY_HASH = "fc719b55087f3a1309c2a19bc6442ce2a98276aa0aedc55e7c501654bb268dd8";
    static final String BINARY_HASH = "f1265757009ff0c85dd8587d9e7bfcf5e51d10d36fe5e1341688215ae1fb49d8";
    static final String ACTIVE = "active-import.json";
    static final int LIMIT = 262144;
    final AppPolicyStore.Storage disk;
    final byte[] factory;
    final String binaryHash;
    final Map<String,Object> trust;
    UperfConfigStore(AppPolicyStore.Storage files, byte[] factoryBytes, String executableHash, byte[] romProfile) throws Exception {
        disk = files; factory = factoryBytes.clone(); binaryHash = executableHash;
        trust = object(parse(romProfile)); keys(trust, "schema", "targetSoC", "factoryHash", "binaryHash", "profiles", "qualifications");
        require(integer(trust.get("schema")) == 1 && string(trust.get("targetSoC")).equals("sm8650"), "ROM compatibility schema/SoC");
        require(string(trust.get("factoryHash")).equals(hash(factory)) && string(trust.get("binaryHash")).equals(binaryHash), "ROM factory/binary compatibility");
        object(trust.get("profiles")); object(trust.get("qualifications")); object(parse(factory));
    }
    static Map<String,Object> flatten(Object value, String path) {
        Map<String,Object> result = new TreeMap<>();
        if (value instanceof Map) {
            Map<String,Object> map = object(value); result.put(path, "@object");
            for (Map.Entry<String,Object> e : map.entrySet()) result.putAll(flatten(e.getValue(), path + "/" + e.getKey().replace("~", "~0").replace("/", "~1")));
        } else if (value instanceof java.util.List) {
            java.util.List<Object> list = array(value); result.put(path, "@array:" + list.size());
            for (int i = 0; i < list.size(); i++) result.putAll(flatten(list.get(i), path + "/" + i));
        } else result.put(path, value);
        return result;
    }
    static String classify(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("gpu") || lower.contains("kgsl") || lower.contains("thermal") || (lower.contains("/sched/") || lower.contains("/sched.")) || lower.startsWith("/modules/sched") || lower.startsWith("/modules/sfanalysis")) return "FORBIDDEN";
        if (path.equals("/meta/name") || path.equals("/meta/author")) return "ALLOWED_CONFIG_ONLY";
        if (path.equals("/initials/sysfs/msmCpuMin") || path.equals("/initials/sysfs/msmCpuMax") || path.matches("/presets/(powersave|balance|performance|fast)/[^/]+/sysfs[./]msmCpu(Min|Max)")
                || path.startsWith("/initials/cpu/") || path.matches("/presets/(powersave|balance|performance|fast)/[^/]+/cpu[./].+")
                || path.startsWith("/modules/cpu/powerModel")) return "REQUIRES_MANUAL_REVIEW";
        if (path.startsWith("/modules/switcher/hintDuration/") || path.startsWith("/modules/input/")
                || path.equals("/modules/log/level") || path.startsWith("/modules/atrace/")
                || path.matches("/initials/sysfs/(cpuset.*|governor.*|inputBoost.*|cpuenable|cpuonlinemin|cpuonlinemax)")
                || path.matches("/presets/[^/]+/[^/]+/sysfs[./](cpuonlinemin|cpuonlinemax)")) {
            if (path.endsWith("/enable") || path.toLowerCase(java.util.Locale.ROOT).contains("path")) return "ROM_REQUIRED";
            return "REQUIRES_MANUAL_REVIEW";
        }
        return "ROM_REQUIRED";
    }
    private void semantic(byte[] base, byte[] payload, Map<String,Object> profile) throws Exception {
        keys(profile, "schema", "binaryHash", "factoryHash", "targetSoC", "fields", "qualificationEvidenceHash");
        require(integer(profile.get("schema")) == 1 && profile.get("binaryHash").equals(binaryHash)
                && profile.get("factoryHash").equals(hash(factory)) && profile.get("targetSoC").equals("sm8650"), "profile identity");
        Map<String,Object> before = flatten(parse(base), ""), after = flatten(parse(payload), "");
        require(before.keySet().equals(after.keySet()), "ROM_REQUIRED_unknown_or_removed_field");
        Map<String,Object> fields = object(profile.get("fields"));
        for (String path : before.keySet()) {
            Object a = before.get(path), b = after.get(path); if (a instanceof Number && b instanceof Number ? new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0 : java.util.Objects.equals(a, b)) continue;
            String category = classify(path); require(!category.equals("FORBIDDEN") && !category.equals("ROM_REQUIRED"), category + ":" + path);
            require(fields.containsKey(path), "unqualified field " + path); Map<String,Object> rule = object(fields.get(path));
            if (category.equals("ALLOWED_CONFIG_ONLY")) {
                keys(rule, "type", "maxUtf8Bytes"); require(rule.get("type").equals("string") && a instanceof String && b instanceof String, "informational type");
                require(string(b).getBytes(StandardCharsets.UTF_8).length <= integer(rule.get("maxUtf8Bytes")) && integer(rule.get("maxUtf8Bytes")) <= 512, "informational bound");
            } else {
                require(path.equals("/initials/sysfs/msmCpuMin") || path.equals("/initials/sysfs/msmCpuMax")
                        || path.matches("/presets/(powersave|balance|performance|fast)/[^/]+/sysfs[./]msmCpu(Min|Max)")
                        || path.startsWith("/initials/cpu/") || path.matches("/presets/(powersave|balance|performance|fast)/[^/]+/cpu[./].+")
                        || path.startsWith("/modules/cpu/powerModel"), "REQUIRES_MANUAL_REVIEW_not_qualified_CPU_family");
                keys(rule, "type", "unit", "topology", "min", "max", "values", "qualificationEvidenceHash");
                require(rule.get("qualificationEvidenceHash").equals(profile.get("qualificationEvidenceHash")), "CPU field qualification");
                require(rule.get("topology").equals("sm8650:0-1,2-4,5-6,7"), "CPU topology profile");
                require(!string(rule.get("unit")).isEmpty(), "qualified field units");
                if (rule.get("type").equals("cpu-khz-list")) {
                    require(path.matches(".*/sysfs[./]msmCpu(Min|Max)"), "CPU list pointer");
                    require(rule.get("unit").equals("kHz") && !array(rule.get("values")).isEmpty(), "CPU list units/OPPs");
                    cpuFrequencies(string(a));
                    for (long frequency : cpuFrequencies(string(b))) {
                        require(frequency >= integer(rule.get("min")) && frequency <= integer(rule.get("max"))
                                && array(rule.get("values")).stream().anyMatch(v -> integer(v) == frequency), "CPU list OPP/sentinel envelope");
                    }
                } else if (rule.get("type").equals("boolean")) require(a instanceof Boolean && b instanceof Boolean && array(rule.get("values")).contains(b), "CPU boolean envelope");
                else {
                    require(a instanceof Number && b instanceof Number && (rule.get("type").equals("number") || rule.get("type").equals("integer")), "CPU numeric type");
                    BigDecimal number = new BigDecimal(b.toString());
                    if (rule.get("type").equals("integer")) number.longValueExact();
                    require(number.compareTo(new BigDecimal(rule.get("min").toString())) >= 0 && number.compareTo(new BigDecimal(rule.get("max").toString())) <= 0, "CPU safety envelope");
                    java.util.List<Object> values = array(rule.get("values"));
                    require(values.isEmpty() || values.stream().anyMatch(v -> v instanceof Number && number.compareTo(new BigDecimal(v.toString())) == 0), "CPU OPP/sentinel qualification");
                    if (path.startsWith("/initials/sysfs/msmCpu")) require(rule.get("unit").equals("kHz") && !values.isEmpty(), "CPU frequency units/OPPs");
                }
            }
        }
        Object tree = parse(payload); Map<String,Object> modules = object(object(tree).get("modules"));
        require(Boolean.FALSE.equals(object(modules.get("sched")).get("enable")) && Boolean.FALSE.equals(object(modules.get("sfanalysis")).get("enable")), "FORBIDDEN_Uperf_owner");
        // Frequencies are the accepted eight CPU:kHz string, not a scalar or a path.
        // A sentinel is allowed only by the exact ROM qualification; never infer an OPP.
        for (String path : after.keySet()) if (path.matches(".*/sysfs[./]msmCpuMin")) {
            long[] min = cpuFrequencies(string(after.get(path)));
            String maxPath = path.substring(0, path.length() - 3) + "Max";
            require(after.containsKey(maxPath), "CPU min/max pair"); long[] max = cpuFrequencies(string(after.get(maxPath)));
            for (int cpu = 0; cpu < 8; cpu++) require(min[cpu] <= max[cpu], "CPU min/max");
        }
    }
    static long[] cpuFrequencies(String value) {
        String[] pairs = value.split(" ", -1); require(pairs.length == 8, "CPU frequency topology"); long[] result = new long[8];
        for (int cpu = 0; cpu < 8; cpu++) {
            require(pairs[cpu].matches(cpu + ":(0|[1-9][0-9]{0,7})"), "CPU frequency map/units");
            result[cpu] = Long.parseLong(pairs[cpu].substring(2));
        }
        return result;
    }
    static Map<String,byte[]> unpack(byte[] archive) throws Exception {
        return unpack(archive,new String[]{"manifest.json","config.json","evidence.json"},new int[]{32768,131072,32768},LIMIT);
    }
    static Map<String,byte[]> unpack(byte[] archive,String[] names,int[] limits,int maximum) throws Exception {
        require(archive.length > 22 && archive.length <= maximum, "archive size");
        // Inspect central attributes too: ZipInputStream intentionally does not expose Unix symlink/executable bits.
        int end = archive.length - 22; require(le(archive, end, 4) == 0x06054b50L && le(archive, end + 20, 2) == 0, "ZIP end");
        require(le(archive, end + 4, 2) == 0 && le(archive, end + 6, 2) == 0 && le(archive, end + 8, 2) == names.length && le(archive, end + 10, 2) == names.length, "ZIP disks/members");
        int at = (int) le(archive, end + 16, 4); require(at + le(archive, end + 12, 4) == end, "ZIP central bound");
        int central = at, local = 0; Map<String,byte[]> result = new HashMap<>();
        for (int index=0;index<names.length;index++) {
            String name=names[index];
            require(le(archive, at, 4) == 0x02014b50L && le(archive, local, 4) == 0x04034b50L, "ZIP entry");
            int flags = (int) le(archive, at + 8, 2), mode = (int) (le(archive, at + 38, 4) >>> 16);
            require((flags & ~0x800) == 0 && le(archive, at + 10, 2) == 0 && (mode & 0170000) == 0100000 && (mode & 0111) == 0, "ZIP nonregular/executable/encrypted/compressed");
            require(le(archive, at + 30, 2) == 0 && le(archive, at + 32, 2) == 0 && le(archive, at + 34, 2) == 0
                    && le(archive, local + 28, 2) == 0 && le(archive, at + 42, 4) == local, "ZIP metadata/offset");
            int size = (int) le(archive, at + 24, 4), length = name.length();
            require(size > 0 && size <= limits[index]
                    && le(archive, at + 20, 4) == size && le(archive, at + 28, 2) == length && le(archive, local + 26, 2) == length, "ZIP member bound");
            require(local + 30 + length + size <= central && at + 46 + length <= end, "ZIP span");
            require(Arrays.equals(name.getBytes(StandardCharsets.US_ASCII), Arrays.copyOfRange(archive, at + 46, at + 46 + length))
                    && Arrays.equals(name.getBytes(StandardCharsets.US_ASCII), Arrays.copyOfRange(archive, local + 30, local + 30 + length)), "ZIP exact ordered names");
            require(Arrays.equals(Arrays.copyOfRange(archive, local + 4, local + 26), Arrays.copyOfRange(archive, at + 6, at + 28)), "ZIP local/central disagreement");
            byte[] data = Arrays.copyOfRange(archive, local + 30 + length, local + 30 + length + size);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(data);
            require(crc.getValue() == le(archive, at + 16, 4), "ZIP CRC"); result.put(name, data);
            local += 30 + length + size; at += 46 + length;
        }
        require(at == end && local == central, "ZIP trailing/unindexed data");
        return result;
    }
    private static long le(byte[] data, int at, int size) {
        require(at >= 0 && at <= data.length - size, "ZIP field bound"); long value = 0;
        for (int i = 0; i < size; i++) value |= ((long) data[at + i] & 255) << (8 * i); return value;
    }
    byte[] payload(String hash) throws Exception {
        require(hash.matches("[0-9a-f]{64}"), "payload hash"); return hash.equals(hash(factory)) ? factory.clone() : disk.read("imports/" + hash + "/config.json");
    }
    void validate(Map<String,byte[]> entries, byte[] base) throws Exception {
        byte[] payload = entries.get("config.json"); Map<String,Object> m = object(parse(entries.get("manifest.json")));
        keys(m, "schemaVersion", "kind", "sourceVersion", "targetSoC", "payloadHash", "acceptedBaseHash", "factoryHash", "expectedUperfBinaryHash", "compatibilityClass", "compatibilityProfileId", "compatibilityProfileHash", "qualifiedConfigPayload", "workflow");
        require(integer(m.get("schemaVersion")) == 1 && m.get("kind").equals("zui.uperf.config-only") && m.get("targetSoC").equals("sm8650"), "import schema/SoC");
        require(string(m.get("sourceVersion")).getBytes(StandardCharsets.UTF_8).length <= 128 && m.get("qualifiedConfigPayload").equals("config.json"), "source identity");
        require(m.get("payloadHash").equals(hash(payload)) && m.get("acceptedBaseHash").equals(hash(base)) && m.get("factoryHash").equals(hash(factory)) && m.get("expectedUperfBinaryHash").equals(binaryHash), "import payload/base/factory/binary");
        require(m.get("compatibilityClass").equals("ALLOWED_CONFIG_ONLY"), "import compatibility class");
        Map<String,Object> profiles = object(trust.get("profiles")); String id = string(m.get("compatibilityProfileId")); require(profiles.containsKey(id), "untrusted compatibility profile");
        Map<String,Object> profile = object(profiles.get(id)); require(m.get("compatibilityProfileHash").equals(hash(bytes(profile))), "profile digest");
        Map<String,Object> workflow = object(m.get("workflow"));
        keys(workflow, "repository", "commit", "runId", "artifactHash", "oldManifestHash", "newManifestHash", "semanticDiffHash", "qualificationEvidenceHash", "attestation");
        for (String key : Arrays.asList("artifactHash", "oldManifestHash", "newManifestHash", "semanticDiffHash", "qualificationEvidenceHash")) require(string(workflow.get(key)).matches("[0-9a-f]{64}"), "workflow digest");
        require(workflow.get("qualificationEvidenceHash").equals(hash(entries.get("evidence.json"))) && workflow.get("qualificationEvidenceHash").equals(profile.get("qualificationEvidenceHash")), "workflow qualification evidence");
        object(parse(entries.get("evidence.json"))); require(string(workflow.get("commit")).matches("[0-9a-f]{40}"), "workflow commit");
        // Governed equivalent attestation: exact manifest digest is pinned by immutable ROM data.
        // Import metadata can neither add a key nor authorize itself. Empty registry rejects all imports.
        String attestation = string(workflow.remove("attestation"));
        Map<String,Object> qualifications = object(trust.get("qualifications"));
        require(qualifications.containsKey(hash(payload)) && qualifications.get(hash(payload)).equals(hash(bytes(m))) && attestation.equals("rom-qualified:" + hash(bytes(m))), "untrusted workflow attestation");
        semantic(base, payload, profile);
    }
    Map<String,Object> selection() throws Exception {
        byte[] bytes = disk.read(ACTIVE);
        if (bytes.length == 0) return map("schema", 1, "generation", 0, "hash", hash(factory), "previous", hash(factory), "state", "ACCEPTED", "rejected", "");
        Map<String,Object> s = object(parse(bytes)); keys(s, "schema", "generation", "hash", "previous", "state", "rejected");
        require(integer(s.get("schema")) == 1 && integer(s.get("generation")) >= 0, "selection schema/generation");
        require(Arrays.asList("PENDING", "ACCEPTED", "ROLLED_BACK").contains(s.get("state")), "selection state");
        for (String k : Arrays.asList("hash", "previous")) require(string(s.get(k)).matches("[0-9a-f]{64}"), "selection hash");
        return s;
    }
    private boolean qualified(String id) {
        try {
            if (id.equals(hash(factory))) return true;
            Map<String,byte[]> entries = new HashMap<>();
            for (String name : Arrays.asList("manifest.json", "config.json", "evidence.json")) entries.put(name, disk.read("imports/" + id + "/" + name));
            Map<String,Object> manifest = object(parse(entries.get("manifest.json"))); String base = string(manifest.get("acceptedBaseHash"));
            byte[] prior = payload(base); require(hash(prior).equals(base), "accepted base missing");
            validate(entries, prior); return hash(entries.get("config.json")).equals(id);
        } catch (Exception e) { return false; }
    }
    byte[] startup() throws Exception {
        Map<String,Object> s = selection(); String selected = string(s.get("hash"));
        if (!qualified(selected)) {
            String previous = string(s.get("previous")); s.put("rejected", selected);
            s.put("hash", qualified(previous) ? previous : hash(factory)); s.put("previous", hash(factory));
            s.put("state", "ROLLED_BACK"); s.put("generation", Math.addExact(integer(s.get("generation")), 1)); disk.write(ACTIVE, bytes(s));
        }
        return payload(string(s.get("hash")));
    }
    long resumeStage(byte[] archive, long expected) throws Exception {
        Map<String,Object> active = selection();
        if (integer(active.get("generation")) == expected) return stage(archive, expected);
        Map<String,byte[]> entries = unpack(archive); String id = hash(entries.get("config.json"));
        Map<String,Object> manifest = object(parse(entries.get("manifest.json")));
        require(integer(active.get("generation")) == Math.addExact(expected, 1) && active.get("hash").equals(id)
                && active.get("previous").equals(manifest.get("acceptedBaseHash")), "indeterminate import; query state");
        for (Map.Entry<String,byte[]> e : entries.entrySet()) require(Arrays.equals(disk.read("imports/" + id + "/" + e.getKey()), e.getValue()), "staged import evidence mismatch");
        require(qualified(id), "staged import qualification"); return integer(active.get("generation"));
    }
    long stage(byte[] archive, long expected) throws Exception {
        Map<String,Object> selected = selection(); require(integer(selected.get("generation")) == expected && !selected.get("state").equals("PENDING"), "stale/import pending");
        Map<String,byte[]> entries = unpack(archive); byte[] base = startup(); validate(entries, base);
        require(integer(selection().get("generation")) == expected, "selection changed during validation");
        String id = hash(entries.get("config.json")); require(!id.equals(selected.get("rejected")), "rejected generation");
        for (Map.Entry<String,byte[]> e : entries.entrySet()) {
            String path = "imports/" + id + "/" + e.getKey(); byte[] prior = disk.read(path);
            require(prior.length == 0 || Arrays.equals(prior, e.getValue()), "immutable import conflict"); if (prior.length == 0) disk.write(path, e.getValue());
        }
        selected.put("previous", selected.get("hash")); selected.put("hash", id); selected.put("state", "PENDING"); selected.put("generation", Math.addExact(expected, 1));
        disk.write(ACTIVE, bytes(selected)); return expected + 1;
    }
    void complete(long generation, boolean ready) throws Exception {
        Map<String,Object> s = selection(); require(integer(s.get("generation")) == generation, "readiness generation conflict");
        if (!s.get("state").equals("PENDING")) return;
        if (ready) s.put("state", "ACCEPTED");
        else {
            String prior = string(s.get("previous")); s.put("rejected", s.get("hash")); s.put("hash", qualified(prior) ? prior : hash(factory));
            s.put("previous", hash(factory)); s.put("state", "ROLLED_BACK"); s.put("generation", Math.addExact(generation, 1));
        }
        disk.write(ACTIVE, bytes(s));
    }
    void reset(long expected) throws Exception {
        Map<String,Object> s = selection(); require(integer(s.get("generation")) == expected && !s.get("state").equals("PENDING"), "reset generation conflict");
        s.put("previous", s.get("hash")); s.put("hash", hash(factory)); s.put("state", "PENDING"); s.put("generation", Math.addExact(expected, 1));
        disk.write(ACTIVE, bytes(s)); // Imports and rejected receipts remain available for inspection.
    }
}
