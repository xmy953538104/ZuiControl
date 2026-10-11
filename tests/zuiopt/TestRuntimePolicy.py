"""The original RT budget labels are replaced exactly without changing other nodes."""
from pathlib import Path
import re,sys,tempfile,unittest
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'scripts/build'))
from ApplyZuiControlPayload import patch_plat_sepolicy

class RuntimePolicy(unittest.TestCase):
 def test_original_oem_task_walt_access_preserved_narrowly(self):
  policy=(ROOT/'payload/patches/plat_sepolicy_zui_control.cil').read_text('utf8')
  grants=re.findall(r'\(allow vendor_hal_perf_default (\S+) \(file \(([^)]+)\)\)\)',policy)
  self.assertEqual(grants,[('zuiopt_walt_task_proc','getattr open read write')])
  nodes=re.findall(r'\(genfscon proc "([^"]+)" \(u object_r zuiopt_walt_task_proc ',policy)
  self.assertEqual(set(nodes),{'/sys/walt/sched_per_task_boost','/sys/walt/task_reduce_affinity'})
  self.assertNotIn('(typeattributeset proc_34_0 (zuiopt_walt_task_proc))',policy)

 def test_exact_replacement_idempotence_and_dry_run(self):
  with tempfile.TemporaryDirectory() as folder:
   root=Path(folder);base=root/'system_a/system/etc/selinux';(base/'mapping').mkdir(parents=True)
   (base/'mapping/34.0.cil').write_text('(typeattribute fixture)\n')
   target=base/'plat_sepolicy.cil'
   untouched='(genfscon proc "/sys/kernel/other" (u object_r proc_sched ((s0) (s0))))\n'
   stock=''.join('(genfscon proc "/sys/kernel/'+n+'" (u object_r proc_sched ((s0) (s0))))\n' for n in ('sched_rt_period_us','sched_rt_runtime_us'))+untouched
   target.write_text(stock);patch_plat_sepolicy(root,ROOT/'payload',True,{})
   self.assertEqual(target.read_text(),stock)
   report={};patch_plat_sepolicy(root,ROOT/'payload',False,report);result=target.read_text()
   self.assertEqual(len(report['plat_sepolicy_removed']),2);self.assertIn(untouched,result)
   for n in ('sched_rt_period_us','sched_rt_runtime_us'):
    self.assertEqual(sum(l.startswith('(genfscon proc "/sys/kernel/'+n+'" ') for l in result.splitlines()),1)
    self.assertIn(n+'" (u object_r zuiopt_rt_budget_proc',result)
   patch_plat_sepolicy(root,ROOT/'payload',False,{});self.assertEqual(target.read_text(),result)
   target.write_text(stock.replace('proc_sched','foreign_budget'))
   with self.assertRaisesRegex(ValueError,'unqualified RT budget'):patch_plat_sepolicy(root,ROOT/'payload',False,{})

if __name__=='__main__':unittest.main()
