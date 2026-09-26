"""Owner R6 reachability and source boundaries; rendering/runtime remain device gates."""
import sys
from pathlib import Path
import hashlib,json,re,unittest,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tests'))
from BackendContract import reverse_text, reverse_entries, entries as scope_entries, MANIFEST as ABC_SCOPE
APP=ROOT/'app/src/main/java/com/zui/zuicontrol'
SERVICE=ROOT/'framework_patch/src/services/com/zui/server/control/ZuiControlService.java'
def source(name):return (APP/name).read_text(encoding='utf8')
def block(text, signature):
    pos=text.index(signature);start=text.index('{',pos);depth=1;end=start+1
    while depth:
        depth+=(text[end]=='{')-(text[end]=='}');end+=1
    return text[pos:end]

class ProductR6(unittest.TestCase):
    def test_popup_single_surface_end_arrow_and_fit(self):
        ui=source('UiControls.kt')
        rows=ui.split('override fun getView',1)[1].split('setOnItemClickListener',1)[0]
        self.assertNotIn('styleChip',rows);self.assertNotIn('shape(',rows)
        self.assertIn('Color.TRANSPARENT',rows)
        self.assertIn('addView(selectedText, LayoutParams(0, -2, 1f))',ui)
        self.assertIn('gravity = Gravity.END',ui)
        self.assertIn('if (popupHeight < items.size * rowHeight + 2 * pad)',ui)
        tokens={x.attrib['name']:x.text for x in ET.parse(ROOT/'app/src/main/res/values/ui_tokens.xml').getroot()}
        self.assertEqual(tokens['ui_dropdown_row_height'],'38dp')
        self.assertLess(5*38+2*4,int(tokens['ui_dropdown_max_height'][:-2]))
        self.assertEqual(tokens['ui_dialog_spacing'],'24dp')
        self.assertEqual(tokens['ui_dialog_vertical_spacing'],'16dp')
        self.assertEqual(tokens['ui_dialog_max_width'],'560dp')
        self.assertIn('r.displayMetrics.widthPixels - 2 * margin',ui)
        self.assertIn('UiControls.styleDialog(this)',source('MainActivity.kt'))
        self.assertIn('gpu_visual_max_width',source('GpuRangeBar.kt'))

    def test_physical_padding_typography_and_order(self):
        monitor=source('PerformanceMonitor.kt')
        metrics=monitor.split('val all = listOf(',1)[1].split('metrics =',1)[0]
        self.assertLess(metrics.index('"fps"'),metrics.index('"quietC"'))
        self.assertLess(metrics.index('"quietC"'),metrics.index('number(displayPower)'))
        self.assertIn('COMPLEX_UNIT_MM, 0.5f, resources.displayMetrics',monitor)
        self.assertIn('2 * barPadding',monitor)
        self.assertIn('monitor_unit_text',monitor)
        tokens={x.attrib['name']:x.text for x in ET.parse(ROOT/'app/src/main/res/values/ui_tokens.xml').getroot()}
        self.assertEqual(tokens['monitor_value_text'],'15sp')
        self.assertEqual(tokens['monitor_unit_text'],'9.6sp')
        self.assertGreater(int(tokens['monitor_unit'][1:3],16),0xB3)
        for name in ('powersave','balance','performance','fast'):
            self.assertIn('R.color.mode_'+name,source('UperfMode.kt'))
        self.assertIn('mode.color',source('UiControls.kt'))
        for name in ('powersave','balance','performance','fast'):
            self.assertIn('R.drawable.notify_mode_'+name,source('NotificationQuickControlHelper.kt'))
            drawable=ET.parse(ROOT/('app/src/main/res/drawable/notify_mode_'+name+'.xml')).getroot()
            self.assertEqual(drawable.find('solid').get('{http://schemas.android.com/apk/res/android}color'),{'powersave':'#15A05C','balance':'#3B67C1','performance':'#EA580C','fast':'#D92424'}[name])
        self.assertIn('ui_mode_group_width',source('MainActivity.kt'))

    def test_shared_authority_and_profile_paths(self):
        quick=source('ZuiControlQuickService.kt');client=source('ZuiControlClient.kt');main=source('MainActivity.kt')
        self.assertIn('it.getCurrentScene()',client)
        self.assertIn('"editableScenePackage"',quick)
        self.assertIn('ZuiControlClient.setCurrentSceneDisplayHz',quick)
        self.assertIn('ZuiControlClient.sendPolicy(this, "mode", pkg, "FOREGROUND"',quick)
        self.assertIn('ZuiControlRequest.send(',client)
        self.assertIn('ZuiControlContract.CMD_SET_UPERF_APP',main)
        self.assertIn('scene = scene',quick)
        for surface in (quick,main):
            self.assertIn('KEY_STATUS_TEXT',surface)
            self.assertIn('KEY_UPERF_RULES_TEXT',surface)
            self.assertIn('registerContentObserver',surface)
            self.assertIn('unregisterContentObserver',surface)
        for prohibited in ('SharedPreferences','SQLite','getRunningTasks','Accessibility','/proc/',
                           'lastPackage','targetGeneration','stateVersion','cachedTarget','Timer','while('):
            self.assertNotIn(prohibited,quick)
        self.assertIn('override fun onResume()',main)
        self.assertIn('reloadState(); renderCurrentPage()',main)
        service=reverse_text('framework_patch/src/services/com/zui/server/control/ZuiControlService.java',SERVICE.read_text(encoding='utf8'))
        for d in json.loads((Path(__file__).with_name('r11_product_delta.json')).read_text())['service']:
            self.assertEqual(service.count(d['after']),1)
            service=service.replace(d['after'],d['before'],1)
        expected=json.loads((Path(__file__).with_name('r6_authority_baseline.json')).read_text())
        for signature,sha in expected.items():
            self.assertEqual(hashlib.sha256(block(service,signature).encode()).hexdigest(),sha,signature)
        self.assertIn('p.equals("com.android.systemui")',block(service,'private static boolean isTransientPackage'))
        self.assertIn('if (transientFocus)',block(service,'private void handleEffectiveFocus'))
        self.assertIn('mLastNonTransientScenePackage = pkg;',block(service,'private void updateBusinessScene'))

    def test_notification_actions_desired_mode_and_record_pages(self):
        quick=source('ZuiControlQuickService.kt')
        self.assertIn('"monitorMode") == "1"',quick)
        self.assertNotIn('monitorActive',quick)
        self.assertNotIn('MONITOR_FPS',quick)
        self.assertNotIn('BigContentView',quick)
        self.assertNotIn('Gpu',quick)
        layout=(ROOT/'app/src/main/res/layout/notification_quick_control.xml').read_text(encoding='utf8')
        renderer=source('NotificationQuickControlHelper.kt')
        self.assertNotIn('fps_toggle',layout)
        for name in ['monitor_toggle','refresh_60','refresh_90','refresh_120','refresh_144','refresh_165','mode_powersave','mode_balance','mode_performance','mode_fast']:
            self.assertIn('@+id/'+name,layout);self.assertIn('R.id.'+name,renderer)
        record=source('PerformanceRecordActivity.kt')
        for phrase in ('应用记录','最近一次记录','线程记录','最低','平均','最高','有效样本'):
            self.assertIn(phrase,record)
        detail=record.split('private fun showDetail(',1)[1].split('private fun showThreads(',1)[0]
        self.assertNotIn('"threads"',detail)
        self.assertIn('navigate(threads = true)',detail)
        self.assertIn('dp(208)',detail)
        self.assertIn('RecordAxis.of',record)
        self.assertNotIn('WebView',record)

if __name__=='__main__':unittest.main(verbosity=2)
