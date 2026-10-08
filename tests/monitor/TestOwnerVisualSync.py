"""Owner R2 viewport/presentation boundary; physical corners remain a device gate."""
from pathlib import Path
import sys,unittest
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'tests'))
from BackendContract import entries,reverse_entries
APP=ROOT/'app/src/main/java/com/zui/zuicontrol'
def read(name):return (APP/name).read_text(encoding='utf8')
class OwnerVisualSync(unittest.TestCase):
 def test_retained_mode_ping_rebinds_without_restarting_motion(self):
  main=read('MainActivity.kt');ui=read('OwnerUi.kt')
  bind=main.split('bindControl(mode){',1)[1].split('detail.addView(owner.title',1)[0]
  self.assertIn('(modeChip.getChildAt(0) as OwnerStatusDot).setTone(owner.tiers[tier])',bind)
  setter=ui.split('fun setTone(color:Int)',1)[1].split('fun retheme',1)[0]
  self.assertIn('tone=color;applyTone()',setter)
  for forbidden in ('ValueAnimator','motion','phase','requestLayout','addView','gateway'):
   self.assertNotIn(forbidden,setter)
  draw=ui.split('internal class OwnerStatusDot',1)[1].split('@SuppressLint',1)[0]
  self.assertIn('setColor(tone)',draw)
  for removed in ('OwnerPulseSurface','OwnerPing','dimPulses','owner_ping_halo','AnimatedVectorDrawable'):
   self.assertNotIn(removed,ui)
  self.assertNotIn('addUpdateListener',draw);self.assertNotIn('postDelayed',draw)
  self.assertIn('masterSelectionBindings+={ruleMarker.visibility=',main)
  self.assertIn('summary.text=if(fresh!=null)',main)
  self.assertIn('rulesButton.contentDescription=title',main)
  status=main.split('private fun threadHome()',1)[1].split('if (ruleError',1)[0]
  self.assertIn('bindPresentation{',status)
  for child in ('runningChip.visibility','statusChip.visibility','statusChip.text=status','statusChip.setTextColor','statusChip.background','statusHost.contentDescription'):
   self.assertIn(child,status)
  load=main.split('private fun loadState()',1)[1].split('private fun loadInventory',1)[0]
  self.assertIn('coreHealthBindings.toList().forEach{it()}',load)
  health=main.split('private fun coreHealth()',1)[1].split('private fun appPage()',1)[0]
  self.assertIn('bindHealth{val fresh=',health);self.assertIn('bindHealth{reason.text=',health)
  self.assertIn('onDismiss={coreHealthBindings.clear();if(visible)render()}',health)
 def test_fullscreen_shared_surface_and_no_platform_dialog(self):
  main=read('MainActivity.kt');record=read('PerformanceRecordActivity.kt');ui=read('OwnerUi.kt')
  for text in (main,):
   self.assertIn('OwnerDesignLayout(this)',text);self.assertIn('clipToOutline=false',text)
   self.assertNotIn('AlertDialog',text);self.assertNotIn('WebView',text)
  self.assertNotIn('shape(owner.detail,32f)',main)
  self.assertIn('RenderEffect.createBlurEffect',ui);self.assertIn('0x8c03060c',ui)
  self.assertIn('body is ScrollView',ui)
  self.assertIn('Intent(this,MainActivity::class.java)',record)
  self.assertNotIn('setContentView',record)
  self.assertNotIn('PerformanceMonitor.command',record)
 def test_frontend_motion_and_frozen_command_owners(self):
  main=read('MainActivity.kt');ui=read('OwnerUi.kt');gpu=read('GpuRangeBar.kt')
  theme=main.split('private fun theme(theme: String)',1)[1].split('private fun monitorGuidance',1)[0]
  self.assertIn('owner.applyTheme(physicalHost',theme)
  self.assertNotIn('render()',theme);self.assertNotIn('removeAllViews',theme)
  self.assertNotIn('recreate()',main);self.assertNotIn('themeReady',main)
  self.assertIn('saveApp(after)',main);self.assertIn('saveGpu(after)',main)
  self.assertIn('animateInk(lit)',ui);self.assertIn('pressThumb(false)',gpu)
  self.assertIn('duration=150',gpu)
  reverse_entries(entries('app','framework_patch','native','payload'),'app','framework_patch','native','payload')
if __name__=='__main__':unittest.main(verbosity=2)
