"""Qualify and rebuild only the OEM BoostFramework DEX plus a CI helper DEX.

All other classes are compared after re-disassembly. No PackageManager patches.
"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import zipfile
from GpuProofTransforms import FRAMEWORK_SHA, patch_boost


def digest(data):
    return hashlib.sha256(data).hexdigest()


def canonical_defaults(data):
    """DEX defaults only, never instructions, permissions, or nonzero API flags.

    https://source.android.com/docs/core/runtime/dex-format: omitted static
    values initialize to zero/null; hidden-API whitelist is encoded as zero.
    Keep the raw representation deltas separately in the qualification receipt.
    """
    lines = data.decode('utf-8').splitlines(keepends=True)
    for i, line in enumerate(lines):
        if line.startswith('.field ') and ' static ' in line:
            line = re.sub(r'(:Z) = false(?=\r?\n|$)', r'\1', line)
            line = re.sub(r'(:I) = 0x0(?=\r?\n|$)', r'\1', line)
            line = re.sub(r'(:L[^;]+;) = null(?=\r?\n|$)', r'\1', line)
        if line.startswith(('.field ', '.method ')):
            line = line.replace(' whitelist ', ' ')
        lines[i] = line
    return ''.join(lines).encode('utf-8')


def build(original, helper, output, classpath, java='java'):
    if os.name == 'nt' and not str(output).startswith('\\\\?\\'):
        output = Path('\\\\?\\'+str(output.resolve()))
    if output.exists():
        raise ValueError('fresh scoped framework output required')
    if digest(original.read_bytes()) != FRAMEWORK_SHA:
        raise ValueError('unqualified original framework')
    if not helper.read_bytes().startswith(b'dex\n'):
        raise ValueError('invalid helper DEX')
    output.mkdir(parents=True)
    def tool(main, *args):
        # Python needs extended paths for long smali names; Java File rejects
        # that prefix during canonicalization, but accepts the ordinary path.
        java_args = [str(arg).removeprefix('\\\\?\\') for arg in args]
        cmd = [java, '-cp', classpath, 'com.android.tools.smali.'+main+'.Main', *java_args]
        receipt = output/('tool_%02d.json' % len(list(output.glob('tool_*.json'))))
        try:
            result = subprocess.run(cmd, capture_output=True, timeout=180)
        except subprocess.TimeoutExpired as error:
            receipt.write_text(json.dumps(dict(argv=cmd, rc=None, timeout_seconds=180,
                stderr=(error.stderr or b'').decode('utf-8', errors='replace')), indent=2), encoding='utf-8')
            raise RuntimeError('tool timeout; evidence: '+str(receipt)) from error
        receipt.write_text(json.dumps(dict(argv=cmd, rc=result.returncode,
            stderr=result.stderr.decode('utf-8', errors='replace')), indent=2), encoding='utf-8')
        if result.returncode:
            raise RuntimeError(str(receipt))
    before = output/'before'
    tool('baksmali', 'disassemble', str(original)+'/classes4.dex', '-j', '2', '-o', before)
    cls = before/'android/util/BoostFramework.smali'
    originals = {p.relative_to(before).as_posix(): p.read_bytes() for p in before.rglob('*.smali')}
    changed = patch_boost(cls.read_bytes())
    cls.write_bytes(changed)
    compiled = output/'classes4.dex'
    # Original 072 uses DEX 039. The qualified dexlib writer must retain that
    # format and API 29 hidden-API metadata (do not use API 28 to drop it).
    tool('smali', 'assemble', before, '--api', '29', '-j', '2', '-o', compiled)
    if compiled.read_bytes()[:8] != b'dex\n039\0':
        raise ValueError('rebuilt DEX format differs from original 072')
    after = output/'after'
    tool('baksmali', 'disassemble', compiled, '-j', '2', '-o', after)
    result = {p.relative_to(after).as_posix(): p.read_bytes() for p in after.rglob('*.smali')}
    if set(result) != set(originals):
        raise ValueError('class set changed')
    # Offsets/labels within the patched method change; all other classes must not.
    representation_deltas = []
    for name, data in originals.items():
        if name == 'android/util/BoostFramework.smali':
            continue
        if canonical_defaults(result[name]) != canonical_defaults(data):
            raise ValueError('unrelated class reassembly delta: '+name)
        if result[name] != data:
            representation_deltas.append(dict(class_name=name, before_sha256=digest(data),
                after_sha256=digest(result[name])))
    def without_acquire(data):
        text = data.decode('utf-8')
        start = text.index('.method public varargs blacklist perfLockAcquire(I[I)I')
        end = text.index('.end method', start) + len('.end method')
        return text[:start]+text[end:]
    if without_acquire(result['android/util/BoostFramework.smali']) != without_acquire(originals['android/util/BoostFramework.smali']):
        raise ValueError('unrelated BoostFramework method delta')
    helper_dec = output/'helper'
    tool('baksmali', 'disassemble', helper, '-j', '2', '-o', helper_dec)
    helper_classes = [p.relative_to(helper_dec).as_posix() for p in helper_dec.rglob('*.smali')]
    if helper_classes != ['android/zui/GpuRequestFilter.smali']:
        raise ValueError('unexpected CI helper classes')
    jar = output/'framework.jar'
    with zipfile.ZipFile(original) as src, zipfile.ZipFile(jar, 'x') as dst:
        if src.testzip() is not None or len(src.namelist()) != len(set(src.namelist())) or 'classes7.dex' in src.namelist():
            raise ValueError('unexpected original ZIP')
        for item in src.infolist():
            dst.writestr(item, compiled.read_bytes() if item.filename == 'classes4.dex' else src.read(item))
        item = zipfile.ZipInfo('classes7.dex', (2009, 1, 1, 0, 0, 0))
        dst.writestr(item, helper.read_bytes())
    receipt = dict(schema='V23_GPU_FRAMEWORK_V1', original_sha256=FRAMEWORK_SHA,
        helper_sha256=digest(helper.read_bytes()), framework_sha256=digest(jar.read_bytes()),
        patched_dex_sha256=digest(compiled.read_bytes()), unchanged_semantic_classes=len(originals)-1,
        representation_only_deltas=representation_deltas,
        changed_method='android.util.BoostFramework.perfLockAcquire(I[I)I',
        package_manager_trust_changed=False, changed_members=['classes4.dex','classes7.dex'])
    receipt['toolchain'] = [dict(path=str(Path(p).resolve()), sha256=digest(Path(p).read_bytes()))
        for p in classpath.split(os.pathsep)]
    (output/'gpu_framework.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')
    return receipt


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    for name in ['original','helper','output']: p.add_argument('--'+name, type=Path, required=True)
    p.add_argument('--classpath', required=True)
    p.add_argument('--java', default='java')
    a = p.parse_args()
    print(json.dumps(build(a.original,a.helper,a.output,a.classpath,a.java)))
