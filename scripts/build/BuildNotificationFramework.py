"""Reassemble one qualified OEM DEX; reuse qualified smali/default and ZIP conventions."""
from pathlib import Path
import hashlib,json,os,re,subprocess,zipfile
from BuildGpuFramework import canonical_defaults
from NotificationProofTransforms import CLASS,GUARD,METHOD,ORIGINAL_DEX_SHA,method_bounds,patch_builder

def digest(data):return hashlib.sha256(data).hexdigest()

def canonical_method(text):
    # Labels encode instruction offsets; retain their exact reference relationships.
    labels={}
    text=re.sub(r'(?m)^(\s*)const-string/jumbo ',r'\1const-string ',text)
    return re.sub(r':(?:cond_[0-9a-f]+|zui_standard_decoration)\b',lambda m:labels.setdefault(m[0],':label_'+str(len(labels))),text)

def build(original,output,classpath,java='java'):
    original=Path(original);output=Path(output)
    if os.name=='nt' and not str(output).startswith('\\\\?\\'):
        output=Path('\\\\?\\'+str(output.resolve()))
    if output.exists():raise ValueError('fresh notification qualification output required')
    with zipfile.ZipFile(original) as z:before_dex=z.read('classes.dex')
    if digest(before_dex)!=ORIGINAL_DEX_SHA:raise ValueError('unqualified notification DEX input')
    output.mkdir(parents=True)
    def tool(main,*args):
        cmd=[java,'-cp',classpath,'com.android.tools.smali.'+main+'.Main',*[str(a).removeprefix('\\\\?\\') for a in args]]
        receipt=output/('tool_%02d.json'%len(list(output.glob('tool_*.json'))))
        try:
            p=subprocess.run(cmd,capture_output=True,timeout=180)
        except subprocess.TimeoutExpired as error:
            receipt.write_text(json.dumps(dict(argv=cmd,rc=None,timeout_seconds=180,
                stdout=(error.stdout or b'').decode('utf8','replace'),stderr=(error.stderr or b'').decode('utf8','replace')),indent=2),encoding='utf8')
            raise RuntimeError('notification assembler timeout: '+str(receipt)) from error
        receipt.write_text(json.dumps(dict(argv=cmd,rc=p.returncode,stdout=p.stdout.decode('utf8','replace'),stderr=p.stderr.decode('utf8','replace')),indent=2),encoding='utf8')
        if p.returncode:raise RuntimeError(str(receipt))
    before=output/'before';tool('baksmali','disassemble',str(original)+'/classes.dex','-j','2','-o',before)
    originals={p.relative_to(before).as_posix():p.read_bytes() for p in before.rglob('*.smali')}
    (output/'original_Notification_Builder.smali').write_bytes(originals[CLASS])
    patched=patch_builder(originals[CLASS]);(before/CLASS).write_bytes(patched)
    compiled=output/'classes.dex';tool('smali','assemble',before,'--api','29','-j','2','-o',compiled)
    if compiled.read_bytes()[:8]!=b'dex\n039\0':raise ValueError('notification DEX format changed')
    after=output/'after';tool('baksmali','disassemble',compiled,'-j','2','-o',after)
    return verify_compiled(output,originals,patched,classpath)

def verify_compiled(output,originals,patched,classpath):
    output=Path(output);after=output/'after';compiled=output/'classes.dex'
    result={p.relative_to(after).as_posix():p.read_bytes() for p in after.rglob('*.smali')}
    if result.keys()!=originals.keys():raise ValueError('notification DEX class set changed')
    representation=[]
    for name,data in originals.items():
        if name==CLASS:continue
        if canonical_defaults(result[name])!=canonical_defaults(data):raise ValueError('unrelated notification class change: '+name)
        if result[name]!=data:representation.append(dict(class_name=name,before=digest(data),after=digest(result[name])))
    a=canonical_defaults(patched).decode().replace('\r\n','\n')
    b=canonical_defaults(result[CLASS]).decode().replace('\r\n','\n')
    a0,a1=method_bounds(a);b0,b1=method_bounds(b)
    if a[:a0]+a[a1:]!=b[:b0]+b[b1:]:raise ValueError('other Notification.Builder method changed')
    # New labels are renamed by reassembly; compare the instruction stream/branches.
    clean=lambda s:'\n'.join(l.strip() for l in canonical_method(s).splitlines() if l.strip())
    if clean(a[a0:a1])!=clean(b[b0:b1]):raise ValueError('notification method round-trip mismatch')
    proof=dict(schema='ZUI_NOTIFICATION_CONTROLLER_V1',original_dex_sha256=ORIGINAL_DEX_SHA,
        patched_dex_sha256=digest(compiled.read_bytes()),changed_method='android.app.Notification$Builder.fullyCustomViewRequiresDecoration(Z)Z',
        target_sdk=35,package='com.zui.zuicontrol',channel='zui_control_monitor_v1',require_system_app=True,
        systemui_changed=False,unchanged_semantic_classes=len(originals)-1,representation_only_deltas=representation,
        transform_sha256=digest(Path(__file__).with_name('NotificationProofTransforms.py').read_bytes()),
        toolchain=[dict(path=p,sha256=digest(Path(p).read_bytes())) for p in classpath.split(os.pathsep)])
    (output/'notification_framework.json').write_text(json.dumps(proof,indent=2),encoding='utf8')
    return proof
