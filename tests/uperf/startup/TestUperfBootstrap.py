"""Execute the production shell with only host path/process fixtures substituted."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
BASH = shutil.which("bash")
PREP = REPO / "payload/system/etc/zui_control/zui_scheduler_prepare.sh"
WRAPPER = REPO / "payload/system/bin/zui_uperf_service"

class BootstrapTests(unittest.TestCase):
    def test_one_control_plane_and_ack_order(self):
        init = (REPO / "payload/system/etc/init/zui_scheduler.rc").read_text()
        prep = PREP.read_text(); wrapper = WRAPPER.read_text()
        self.assertNotIn("app_process", wrapper.split("AUTH=")[1])
        self.assertNotIn("PolicyCommand", wrapper)
        self.assertEqual(prep.count("PolicyCommand bootstrap -"), 1)
        self.assertLess(prep.index("PolicyCommand bootstrap -"), prep.index("PolicyCommand uperf-startup -"))
        self.assertLess(prep.index("PolicyCommand uperf-startup -"), prep.index("setprop zui_control.scheduler prepared"))
        boot = init.split("on property:sys.boot_completed=1")[1].split("\non ")[0]
        self.assertNotIn("trigger zui-scheduler-start", boot)
        self.assertIn("start zui_scheduler_prepare", boot)
        self.assertIn("on property:zui_control.scheduler=prepared", init)
        domain = (REPO / "payload/system/etc/init/zui_controld.rc").read_text().split("service zui_scheduler_prepare ")[1].split("\non ")[0]
        self.assertIn("seclabel u:r:shell:s0", domain)
        self.assertIn("group root system shell readproc", domain)
        self.assertIn("rm /data/vendor/zui_control/uperf/.validated_runtime.sha256", init)
        self.assertNotIn("app_process", (REPO / "payload/patches/plat_sepolicy_zui_control.cil").read_text())

    def test_exact_scripts_with_success_failure_and_restart(self):
        self.assertIsNotNone(BASH, "host bash required")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); data = root / "data"; data.mkdir(); (data / "uperf").mkdir()
            commands = root / "bin"; commands.mkdir()
            # Host executables emulate only Android boundaries; production script is executed unchanged otherwise.
            fixtures = {
                "setprop": 'echo "$*" >> "$FIXTURE/events"',
                "sync": 'exit 0', "restorecon_recursive": 'exit 0',
                "settings": 'echo balance',
                "app_process": 'echo "$3" >> "$FIXTURE/calls"; [ "$3" != "$FAIL_ACTION" ] || exit 9; if [ "$3" = uperf-startup ]; then echo qualified > "$FIXTURE/data/uperf/uperf.json"; sha256sum "$FIXTURE/data/uperf/uperf.json" "$FIXTURE/binary" > "$FIXTURE/data/uperf/.validated_runtime.sha256"; fi',
                "supervisor": 'echo launched >> "$FIXTURE/launches"',
            }
            for name, body in fixtures.items():
                f = commands / name; f.write_text("#!/bin/sh\n" + body + "\n"); f.chmod(0o755)
            (root/'binary').write_text('pinned executable')
            env = dict(os.environ, FIXTURE=root.as_posix(), PATH=commands.as_posix()+os.pathsep+os.environ['PATH'])
            prep = PREP.read_text().replace('/data/vendor/zui_control', data.as_posix()).replace('/system/bin/app_process', 'app_process')
            wrapper = WRAPPER.read_text().replace('/data/vendor/zui_control', data.as_posix()).replace('/system/bin/zui_uperf_supervisor', (commands/'supervisor').as_posix()).replace('/dev/cpuset/background/tasks', (root/'tasks').as_posix())
            wrapper = wrapper.replace('BINARY=/system/bin/uperf', 'BINARY="'+(root/'binary').as_posix()+'"')
            hostbin = subprocess.check_output([BASH, '-c', 'cd "$1" && pwd', 'fixture', str(commands)], text=True).strip()
            prefix = 'export PATH="' + hostbin + ':$PATH"\n'
            script = root / 'prepare.sh'; script.write_text(prefix + prep); service = root / 'service.sh'; service.write_text(prefix + wrapper)
            u = data/'uperf'; (u/'cur_powermode.txt').write_text('balance\n'); (u/'perapp_powermode.txt').write_text('game.app fast\n')
            def run(p, **extra): return subprocess.run([BASH, str(p), 'powersave'],env=dict(env,**extra),capture_output=True)
            self.assertNotEqual(run(service).returncode,0)
            result=run(script); self.assertEqual(result.returncode,0, result.stderr.decode()+((data/"log/bootstrap.log").read_text() if (data/"log/bootstrap.log").exists() else ""))
            self.assertEqual((u/'cur_powermode.txt').read_text(),'balance\n')
            self.assertEqual((u/'effective_powermode.txt').read_text(),'powersave\n')
            self.assertEqual((root/'calls').read_text().splitlines(),['bootstrap','uperf-startup','uperf-observe-startup'])
            before=(root/'calls').read_bytes()
            for _ in range(2): self.assertEqual(run(service).returncode,0)
            self.assertEqual((root/'calls').read_bytes(),before)
            (root/'binary').write_text('tampered executable')
            self.assertNotEqual(run(service).returncode,0)
            (root/'binary').write_text('pinned executable')
            (u/'uperf.json').write_text('stale')
            self.assertNotEqual(run(service).returncode,0)
            self.assertNotEqual(run(script,FAIL_ACTION='bootstrap').returncode,0)
            self.assertFalse((u/'.validated_runtime.sha256').exists())
            self.assertNotEqual(run(script,FAIL_ACTION='uperf-startup').returncode,0)
            self.assertFalse((u/'.validated_runtime.sha256').exists())
            self.assertEqual((root/'events').read_text().splitlines().count('zui_control.scheduler prepared'),1)
            self.assertNotEqual(run(service).returncode,0)
            self.assertEqual((root/'launches').read_text().splitlines(),['launched','launched'])
            self.assertIn('sys.zui_control.uperf_fail_safe 1',(root/'events').read_text())

if __name__ == '__main__': unittest.main(verbosity=2)
