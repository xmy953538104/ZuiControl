"""Direct hierarchy, durable timeout and fresh UI regressions; no device access."""
import base64
import inspect
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import retirement as R

class ReleaseProof(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.path=Path(self.temp.name);self.cg=self.path/'cpuset/asopt';self.cg.parent.mkdir()
        self.d=R.Device('unused',self.path);self.running=False;self.service='stopped'
        self.shell=shutil.which('sh')
        if not self.shell and os.name=='nt':
            candidate=Path(__file__).resolve().parents[5]/'Edit tools/Git/PortableGit/bin/sh.exe'
            if candidate.is_file():self.shell=str(candidate)
        # Explicit Git Bash is a host POSIX shell, not a device or UID rollback fixture.
        if not self.shell and os.name=='nt':self.shell=os.environ.get('ZUIOPT_TEST_SH')
        if not self.shell:self.skipTest('POSIX shell required for generated read-only shell fixture')
        def root(command,timeout=60):
            command=command.replace('/dev/cpuset',self.cg.parent.as_posix()).replace('/proc/',(self.path/'proc').as_posix()+'/')
            command=command.replace('$(pidof AsoulOpt || true)','123' if self.running else '')
            command=command.replace('$(getprop init.svc.zui_asoulopt)',self.service)
            p=subprocess.run([self.shell,'-c','set -eu; '+command],capture_output=True,text=True,timeout=timeout)
            R.need(p.returncode==0,'fixture root failed '+p.stderr)
            return p.stdout.replace(self.cg.parent.as_posix(),'/dev/cpuset')
        self.d.root=root
    def tasks(self,values=''):
        self.cg.mkdir(exist_ok=True);(self.cg/'tasks').write_bytes(b'')
        child=self.cg/'7c';child.mkdir(exist_ok=True);(child/'tasks').write_bytes(values.encode('ascii'))
    def survivor(self,value):
        p=self.path/'proc/45';p.mkdir(parents=True);(p/'cpuset').write_bytes((value+'\n').encode('ascii'))
    def test_fixture_posix_bytes(self):
        self.tasks('45\n');self.survivor('/top-app')
        self.assertEqual((self.cg/'tasks').read_bytes(),b'')
        self.assertEqual((self.cg/'7c/tasks').read_bytes(),b'45\n')
        self.assertEqual((self.path/'proc/45/cpuset').read_bytes(),b'/top-app\n')
    def test_running_fails(self):
        self.running=True
        with self.assertRaises(RuntimeError):self.d.released()
    def test_init_running_fails(self):
        self.service='running'
        with self.assertRaises(RuntimeError):self.d.released()
    def test_absent_pass(self):self.assertEqual(self.d.released()['retired_cpuset_tasks'],0)
    def test_empty_descendants_pass(self):self.tasks();self.assertEqual(self.d.released()['status'],'PASS')
    def test_descendant_tid_fails(self):
        self.tasks('45\n')
        with self.assertRaisesRegex(RuntimeError,'ASOPT_TASK_FOUND'):self.d.released()
    def test_exited_tid_pass(self):self.assertEqual(self.d.released([45])['status'],'PASS')
    def test_moved_survivor_pass(self):self.survivor('/top-app');self.assertEqual(self.d.released([45])['status'],'PASS')
    def test_retired_survivor_fails(self):
        self.survivor('/asopt/7c')
        with self.assertRaises(RuntimeError):self.d.released([45])
    def test_unreadable_tasks_fail_closed(self):
        self.tasks();(self.cg/'7c/tasks').unlink();(self.cg/'7c/tasks').mkdir()
        with self.assertRaises(RuntimeError):self.d.released()
    def test_capture_only_hierarchy_and_deadline(self):
        self.tasks('45\n');self.assertEqual(self.d.retired_tasks(),[45]);(self.cg/'7c/tasks').write_bytes(b'')
        result=self.d.released();self.assertLess(result['duration_ms'],2000)
    def test_no_full_proc_scan_or_per_tid_cat(self):
        source=inspect.getsource(R)
        self.assertNotIn('/proc/'+'[0-9]*/task/',source)
        self.assertNotIn('cat ',R.CPUSET_READ)
        self.assertLessEqual(R.RELEASE_TIMEOUT,15)
    def test_invalid_partial_output_fails(self):
        for text in ('','TID=5\n','TASK_FILE=/unrelated/tasks\n','HIERARCHY=ABSENT\nTASK_FILE=/dev/cpuset/asopt/tasks\n'):
            with self.assertRaises(RuntimeError):R.parse_tasks(text)

class CommandReceipts(unittest.TestCase):
    def test_timeout_durable_before_dispatch(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp);d=R.Device('fixture-adb',p)
            def run(argv,**kwargs):
                start=json.loads((p/'00001-command-start.json').read_text())
                self.assertEqual(start['argv'],argv);self.assertEqual(start['timeout'],.01)
                raise subprocess.TimeoutExpired(argv,.01,output=b'partial-out',stderr=b'partial-err')
            with patch.object(R.subprocess,'run',side_effect=run):
                with self.assertRaises(subprocess.TimeoutExpired):d.call(['test'],timeout=.01)
            result=json.loads((p/'00001-command-timeout.json').read_text())
            self.assertEqual(base64.b64decode(result['partial_stdout_b64']),b'partial-out')
            self.assertEqual(base64.b64decode(result['partial_stderr_b64']),b'partial-err')
            self.assertGreaterEqual(result['elapsed'],0)
    def test_real_subprocess_timeout_preserves_partial(self):
        with tempfile.TemporaryDirectory() as tmp:
            d=R.Device(sys.executable,Path(tmp))
            # ADB prefix is -s SERIAL; mock only argv construction, run a real child.
            original=subprocess.run
            def child(argv,**kwargs):return original([sys.executable,'-c','import sys,time; print("partial",flush=True); time.sleep(5)'],**kwargs)
            with patch.object(R.subprocess,'run',side_effect=child):
                with self.assertRaises(subprocess.TimeoutExpired):d.call([],timeout=.3)
            row=json.loads((Path(tmp)/'00001-command-timeout.json').read_text())
            self.assertIn(b'partial',base64.b64decode(row['partial_stdout_b64']))
    def test_nonzero_result_preserved(self):
        with tempfile.TemporaryDirectory() as tmp:
            d=R.Device('adb',Path(tmp))
            with patch.object(R.subprocess,'run',return_value=subprocess.CompletedProcess([],9,b'o',b'e')):
                with self.assertRaises(RuntimeError):d.call([])
            self.assertEqual(json.loads((Path(tmp)/'00001-command-result.json').read_text())['returncode'],9)
    def test_root_zero_outer_rc_not_enough(self):
        d=R.Device('adb',Path('.'))
        d.call=lambda args,**kw:b'not a child receipt\n'
        with self.assertRaisesRegex(RuntimeError,'root child failed'):d.root('false')

def fixture_xml(label=None,y=100):
    # Minimal structural regression derived from prior real Threads XML; no device data.
    button='' if label is None else f'<node text="" package="com.zui.zuicontrol" clickable="true" enabled="true" bounds="[0,{y}][400,{y+80}]"><node text="{label}" package="com.zui.zuicontrol"/></node>'
    return ET.fromstring('<hierarchy><node package="com.zui.zuicontrol"><node text="Task Scheduler" package="com.zui.zuicontrol"/><node text="线程" package="com.zui.zuicontrol" clickable="true" enabled="true" bounds="[0,900][400,980]"/><node scrollable="true" package="com.zui.zuicontrol" bounds="[0,100][400,800]">'+button+'</node></node></hierarchy>')

class UiNavigation(unittest.TestCase):
    def navigate(self,xmls,label,max_scrolls=8):
        d=R.Device('unused',Path('.'));calls=[];frames=iter(xmls)
        d.call=lambda args,**kw:calls.append(args)
        d.ui_xml=lambda:next(frames)
        d.product_button(label,max_scrolls)
        return calls
    def test_visible_stop(self):
        calls=self.navigate([fixture_xml('停止 AsoulOpt')],'停止 AsoulOpt')
        self.assertEqual(calls[-1],['shell','input','tap','200','140'])
    def test_rerender_offscreen_enable(self):
        lower=fixture_xml('启用 AsoulOpt',500)
        lower.find('node').remove(lower.find('node/node')) # Header scrolled offscreen.
        calls=self.navigate([fixture_xml(),lower],'启用 AsoulOpt')
        self.assertEqual(calls[-1],['shell','input','tap','200','540'])
        self.assertEqual(sum('swipe' in c for c in calls),1)
    def test_visible_target_precedes_navigation(self):
        root=fixture_xml('启用 AsoulOpt',500)
        root.find('node').remove(root.find('node/node'))
        calls=self.navigate([root],'启用 AsoulOpt')
        self.assertEqual(calls[-1],['shell','input','tap','200','540'])
        self.assertEqual(sum('tap' in c for c in calls),1)
    def test_navigation_then_fresh_target(self):
        first=fixture_xml();first.find('node').remove(first.find('node/node'))
        calls=self.navigate([first,fixture_xml('停止 AsoulOpt',300)],'停止 AsoulOpt')
        self.assertEqual(calls[-1],['shell','input','tap','200','340'])
        self.assertEqual(sum('tap' in c for c in calls),2)
    def test_no_stale_coordinates(self):
        a=self.navigate([fixture_xml('停止 AsoulOpt',100)],'停止 AsoulOpt')
        b=self.navigate([fixture_xml(),fixture_xml('启用 AsoulOpt',600)],'启用 AsoulOpt')
        self.assertNotEqual(a[-1],b[-1])
    def test_wrong_package(self):
        root=ET.fromstring('<hierarchy><node package="evil" text="启用 AsoulOpt"/></hierarchy>')
        with self.assertRaises(RuntimeError):self.navigate([root],'启用 AsoulOpt')
    def test_duplicate_exact_label(self):
        root=fixture_xml('启用 AsoulOpt');root.append(ET.fromstring('<node text="启用 AsoulOpt" package="com.zui.zuicontrol"/>'))
        with self.assertRaises(RuntimeError):self.navigate([root],'启用 AsoulOpt')
    def test_bound_exhaustion(self):
        with self.assertRaisesRegex(RuntimeError,'bounded scroll exhausted'):self.navigate([fixture_xml()]*3,'启用 AsoulOpt',2)
    def test_exact_not_substring(self):
        self.assertIsNone(R.ui_button(fixture_xml('启用 AsoulOpt extra'),'启用 AsoulOpt',optional=True))
    def test_real_previous_xml(self):
        folder=os.environ.get('ZUIOPT_REAL_UI_FIXTURES')
        if not folder:return # CI uses structural fixtures; actual retained XML exercised locally.
        p=Path(folder)
        stop=ET.parse(p/'ui_04/ui.xml').getroot()
        top=ET.parse(p/'rollback_ui_01/ui.xml').getroot()
        enable=ET.parse(p/'rollback_ui_02/ui.xml').getroot()
        self.assertIsNotNone(R.ui_button(stop,'停止 AsoulOpt'))
        stop_calls=self.navigate([stop],'停止 AsoulOpt')
        self.assertEqual(stop_calls[-1][3:],list(map(str,R.ui_button(stop,'停止 AsoulOpt'))))
        self.assertIsNone(R.ui_button(top,'启用 AsoulOpt',optional=True))
        calls=self.navigate([top,enable],'启用 AsoulOpt')
        self.assertEqual(calls[-1][3:],list(map(str,R.ui_button(enable,'启用 AsoulOpt'))))

if __name__=='__main__':unittest.main(verbosity=2)
