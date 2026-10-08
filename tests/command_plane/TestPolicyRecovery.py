"""Compose exact service admission/transaction slices with real AppPolicyStore and native peer.

Arguments form a native runner prefix: RecoveryPeer /absolute/temporary/root
or adb -s SERIAL exec-out /data/local/tmp/.../peer /data/local/tmp/... .
The caller supplies an isolated temporary directory, never Android product paths.
"""
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'framework_patch/src/services/com/zui/server/control'


def main(prefix):
    client=(ROOT/'app/src/main/java/com/zui/zuicontrol/ZuiControlRequest.kt').read_text(encoding='utf8')
    assert 'val isTerminal: Boolean get() = state == ACK_DONE || state == ACK_FAILED' in client
    assert 'clearPending(context, requestId)\n                    return ack' in client
    source=(BASE/'ZuiControlService.java').read_text(encoding='utf8')
    start=source.index('                byte[] previous = mAppPolicies.disk.read("policy-request.json");')
    admission=source[start:source.index('\n            } else if ("set_uperf_mode"',start)]
    start=source.index('            AppPolicyStore.State current = mAppPolicies.current;',source.index('private synchronized String policyCommand('))
    policy=source[start:source.index('            if ("bootstrap".equals(requestId)) {',start)]
    boundary='PolicyJson.require(requestId.equals(request.get("id")) && requestHash.equals(request.get("sha")), "authenticated policy request");'
    assert policy.count(boundary)==1
    policy=policy.replace(boundary,boundary+'\n                hit("request_authenticated");')
    harness=Path(__file__).with_name('RecoveryFixture.java').read_text(encoding='utf8')
    harness=harness.replace('/* ADMISSION */',admission).replace('/* POLICY */',policy)
    start=source.index('    private String requestRefused(')
    end=source.index('{',start)+1
    depth=1
    while depth:
        depth+=(source[end]=='{')-(source[end]=='}');end+=1
    refusal=source[start:end]
    start=source.index('    private static boolean validRequestId(')
    refusal+=source[start:source.index('    private static boolean validSha256(',start)]
    harness=harness.replace('/* REFUSAL */',refusal)
    # reconcileUsers is part of the exact slice; fixture user inventory stays unchanged.
    with tempfile.TemporaryDirectory() as directory:
        java=Path(directory)/'RecoveryFixture.java';java.write_text(harness,encoding='utf8')
        subprocess.run(['javac','-encoding','UTF-8','-d',directory,
            *[str(BASE/n) for n in ('PolicyJson.java','GpuRange.java','AppPolicyStore.java','RequestIdentity.java')],str(java)],check=True)
        subprocess.run(['java','-cp',directory,'com.zui.server.control.RecoveryFixture',*prefix],check=True,timeout=180)


if __name__=='__main__':main(sys.argv[1:])
