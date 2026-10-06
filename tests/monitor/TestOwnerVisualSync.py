"""Owner R2 viewport/presentation boundary; physical corners remain a device gate."""
from pathlib import Path
import sys,unittest
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'tests'))
from BackendContract import entries,reverse_entries
APP=ROOT/'app/src/main/java/com/zui/zuicontrol'
def read(name):return (APP/name).read_text(encoding='utf8')
class OwnerVisualSync(unittest.TestCase):
 def test_fullscreen_shared_surface_and_no_platform_dialog(self):
  main=read('MainActivity.kt');record=read('PerformanceRecordActivity.kt');ui=read('OwnerUi.kt')
  for text in (main,record):
   self.assertIn('OwnerDesignLayout(this)',text);self.assertIn('clipToOutline=false',text)
   self.assertNotIn('AlertDialog',text);self.assertNotIn('WebView',text)
  self.assertNotIn('shape(owner.detail,32f)',main)
  self.assertIn('RenderEffect.createBlurEffect',ui);self.assertIn('0x8c03060c',ui)
  self.assertIn('body is ScrollView',ui)
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
