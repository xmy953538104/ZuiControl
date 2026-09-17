"""Narrow V23 source-bound transforms. No device I/O or generic trust patches."""
import hashlib
import re
import xml.etree.ElementTree as ET

FRAMEWORK_SHA = 'b5f57d62546569b9bd9ba34d8757678da000c33f876358dd93a4dc747b8f1b32'
TASSISTENT_SHA = 'bd11fdc3cf43e9a75bd42dd91ecc807d4cc768932655c2ba61f60c0ad10d2097'
TASSISTENT_CERT = '6fa8cec6ad4c46b95743787fb181997a69e68f79a8a4a5aa8588d7e228c51b36'
BOOST_SMALI_SHA = 'd81634498f02b20fb65d07bda18de8c8cd56e1662d43a53680029cfa44e7245f'
GPU_CONFIGS = {
    'system_a/system/etc/performanceconfig.xml': 'fb88f54eb9e7846c60a0ea3677a63094f2398a6c7463a1c27a0f01dedec43396',
    'vendor_a/etc/pwr/GamePowerOptFeature.xml': '57c3b177efa327beef7e3dcb2abbddd5defe27bcb2d5d19b0f6f69f89c94dfc1',
}

FILTER_ENTRY = '''    invoke-static {}, Landroid/os/Process;->myUid()I
    move-result v0
    invoke-static {}, Landroid/app/ActivityThread;->currentPackageName()Ljava/lang/String;
    move-result-object v1
    invoke-static {}, Landroid/app/ActivityThread;->currentProcessName()Ljava/lang/String;
    move-result-object v2
    invoke-static {v0, v1, v2}, Landroid/zui/GpuRequestFilter;->isTarget(ILjava/lang/String;Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :zui_gpu_original
    invoke-static {p2}, Landroid/zui/GpuRequestFilter;->filter([I)[I
    move-result-object p2
    if-eqz p2, :zui_gpu_no_acquire
    array-length v0, p2
    if-nez v0, :zui_gpu_original
    :zui_gpu_no_acquire
    invoke-virtual {p0}, Landroid/util/BoostFramework;->perfLockRelease()I
    move-result v0
    if-gez v0, :zui_gpu_rejected
    const-string v1, "ZuiGpuFilter"
    const-string v2, "empty/malformed request: release failed"
    invoke-static {v1, v2}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I
    :zui_gpu_rejected
    const/4 v0, -0x1
    return v0
    :zui_gpu_original

'''


def patch_boost(smali):
    if hashlib.sha256(smali).hexdigest() != BOOST_SMALI_SHA:
        raise ValueError('unqualified BoostFramework smali')
    ending = '\r\n' if b'\r\n' in smali else '\n'
    text = smali.decode('utf-8').replace('\r\n', '\n')
    start = text.index('.method public varargs blacklist perfLockAcquire(I[I)I')
    end = text.index('.end method', start)
    method = text[start:end]
    needle = '    .line 342\n'
    if method.count(needle) != 1 or '.registers 9' not in method:
        raise ValueError('unexpected acquire body')
    modified = method.replace(needle, FILTER_ENTRY + needle, 1)
    return (text[:start] + modified + text[end:]).replace('\n', ending).encode('utf-8')


def gamepower(data):
    ET.fromstring(data)
    pattern = rb'(<GPU_FREQ_MAX_Enable>\s*)([01])(\s*</GPU_FREQ_MAX_Enable>)'
    matches = list(re.finditer(pattern, data))
    root = ET.fromstring(data)
    profiles = root.findall('Profiles/Profile')
    if not profiles or len(matches) != len(profiles) or any(len(p.findall('GPU_FREQ_MAX_Enable')) != 1 for p in profiles):
        raise ValueError('unexpected GamePowerOpt profiles')
    out = re.sub(pattern, lambda m: m[1] + b'0' + m[3], data)
    return out, sum(m[2] != b'0' for m in matches)


def performance(data):
    ET.fromstring(data)
    # Preserve byte layout and all unrelated fields, including thresholds and CPU.
    nodes = ET.fromstring(data).findall('.//Type[@name="GPU"]')
    types = list(re.finditer(rb'<Type\s+name\s*=\s*"GPU"\s*>(.*?)</Type>', data, re.S))
    if len(nodes) != 1 or len(types) != 1:
        raise ValueError('GPU Type must be unique')
    t = types[0]
    slots = list(re.finditer(rb'(<Freq\s+level\s*=\s*"(-?\d+)"\s*>)(-?\d+_-?\d+_-?\d+)(</Freq>)', t[1]))
    if not slots or len(slots) != len(nodes[0].findall('Freq')):
        raise ValueError('unrecognized GPU tuple')
    if len({s[2] for s in slots}) != len(slots):
        raise ValueError('duplicate GPU ID')
    count = sum(s[3] != b'-1_-1_-1' for s in slots)
    out = data
    for s in reversed(slots):
        a, b = t.start(1) + s.start(3), t.start(1) + s.end(3)
        out = out[:a] + b'-1_-1_-1' + out[b:]
    removed = 0
    for name in (b'Full', b'GPUTest'):
        item = rb'(<Item\s+name\s*=\s*"' + name + rb'"\s*>)(.*?)(</Item>)'
        found = list(re.finditer(item, out, re.S))
        if len(found) != 1:
            raise ValueError('missing/duplicate performance item')
        m = found[0]
        gpu = rb'<PerfLockConfig\s+code\s*=\s*"0x(?:4280C000|42804000)"\s+param\s*=\s*"\d+"\s*/>'
        body, n = re.subn(gpu, b'', m[2])
        if n not in (0, 2):
            raise ValueError('partial independent GPU pair')
        removed += n
        out = out[:m.start(2)] + body + out[m.end(2):]
    ET.fromstring(out)
    return out, dict(tuple_changes=count, independent_resources_removed=removed)


def apply_gpu_configs(unpack, dry_run=False):
    """Only the exact OEM inputs. Validate every input before the first write."""
    apk = unpack/'system_a/system/app/TAssistent/TAssistent.apk'
    if apk.is_symlink() or hashlib.sha256(apk.read_bytes()).hexdigest() != TASSISTENT_SHA:
        raise ValueError('frozen OEM TAssistent identity mismatch')
    pending = []
    report = {'tassistent_sha256': TASSISTENT_SHA, 'tassistent_changed': False, 'files': {}}
    for relative, expected in GPU_CONFIGS.items():
        path = unpack/relative
        before = path.read_bytes()
        if path.is_symlink() or hashlib.sha256(before).hexdigest() != expected:
            raise ValueError('unqualified GPU config: '+relative)
        after, counts = (performance if path.name == 'performanceconfig.xml' else gamepower)(before)
        required = dict(tuple_changes=411, independent_resources_removed=4) if path.name == 'performanceconfig.xml' else 2
        if counts != required:
            raise ValueError('unexpected qualified GPU delta count')
        report['files'][relative] = dict(before_sha256=expected,
            after_sha256=hashlib.sha256(after).hexdigest(), delta=counts)
        pending.append((path, after))
    if not dry_run:
        for path, after in pending:
            path.write_bytes(after)
    return report
