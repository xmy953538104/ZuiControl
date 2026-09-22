"""Scoped R7 source contracts; actual gestures, rendering and runtime are device gates."""
from pathlib import Path
import re,unittest,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2];APP=ROOT/'app/src/main/java/com/zui/zuicontrol'
def read(name):return (ROOT/name).read_text(encoding='utf8')
def app(name):return (APP/name).read_text(encoding='utf8')

class ProductR7(unittest.TestCase):
    def test_selection_calls_and_policy_reachability(self):
        ui=app('UiControls.kt');main=app('MainActivity.kt');quick=app('ZuiControlQuickService.kt')
        self.assertNotIn('fun setSelection(',ui)
        self.assertIn('this@AnchoredDropdown.commitSelection(position)',ui)
        self.assertEqual(main.count('picker.commitSelection('),2)
        self.assertNotIn('picker.setSelection(',main)
        self.assertIn('UperfAppPolicy.isConfigurable(packageManager, it.info.packageName)',main)
        self.assertIn('check(UperfAppPolicy.isConfigurable(packageManager, pkg))',main)
        self.assertIn('check(UperfAppPolicy.isConfigurable(packageManager, pkg))',quick)
        self.assertIn('setBoolean(id, "setEnabled", uperfEnabled)',quick)
        self.assertIn('UperfAppPolicy.isConfigurable(packageManager, pkg)',quick)

    def test_shared_policy_and_bounded_queries(self):
        policy=app('UperfAppPolicy.kt');daemon=read('payload/system/bin/zui_controld')
        roots=re.search(r'allowedRoots = listOf\((.*?)\)',policy)[1]
        self.assertEqual(re.findall('"([^"]+)"',roots),['/data/app/','/system/preinstall/'])
        roots_case=re.search(r'case "\$1" in (/data/app/.*?)\) return 0',daemon)[1]
        self.assertEqual(roots_case,'/data/app/?*.apk|/system/preinstall/?*.apk')
        for name in ('android','com.android.systemui','com.zui.zuicontrol'):
            self.assertIn('"'+name+'"',policy)
        self.assertIn('android|com.android.systemui|com.zui.zuicontrol',daemon)
        for flag in ('activity.enabled','activity.exported','activity.packageName == pkg','app.enabled','File(it).canonicalPath','File(it).isFile'):
            self.assertIn(flag,policy)
        self.assertIn('paths.isNotEmpty()',policy)
        self.assertIn('paths.zip(canonical).all',policy)
        self.assertIn('timeout 5 pm path --user current',daemon)
        self.assertIn('timeout 5 cmd package resolve-activity --user current',daemon)
        self.assertIn('[ -f "$apk" ] && [ "$(readlink -f "$apk" 2>/dev/null)" = "$apk" ]',daemon)
        self.assertIn('done <<EOF_UPERF_PATHS',daemon)
        self.assertIn('docs/UPERF_CONFIGURABLE_APP.md',policy)

    def test_monitor_home_and_safe_insets(self):
        service=read('framework_patch/src/services/com/zui/server/control/ZuiControlService.java')
        monitor=service.split('private synchronized void refreshMonitor(',1)[1].split('\n    private ',1)[0]
        self.assertNotIn('CATEGORY_HOME',monitor)
        for token in ('mScreenInteractive','!lock.isKeyguardLocked()','eligible'):
            self.assertIn(token,monitor)
        overlay=app('PerformanceMonitor.kt')
        for token in ('WindowInsets.Type.statusBars()','WindowInsets.Type.displayCutout()',
                      'WindowInsets.Type.captionBar()','setFitInsetsTypes(0)','setOnApplyWindowInsetsListener',
                      'Gravity.TOP or Gravity.CENTER_HORIZONTAL'):
            self.assertIn(token,overlay)
        self.assertNotIn('com.zui.launcher',overlay+monitor)
        session=read('framework_patch/src/services/com/zui/server/control/MonitorSession.java')
        self.assertNotIn('mode = 1;',session)

    def test_owner_ui(self):
        main=app('MainActivity.kt');ui=app('UiControls.kt')
        header=main.split('private fun headerStatusText()',1)[1].split('\n    private ',1)[0]
        self.assertNotIn('Hz',header);self.assertIn('v$version',header)
        self.assertIn('UPERF("性能"',main)
        self.assertIn('label("Hz", 17f, COLOR_SUBTLE, Typeface.NORMAL)',main)
        self.assertIn('R.color.ui_text',ui.split('fun modeChip',1)[1].split('fun ',1)[0])
        settings=main.split('private fun buildSystemPage()',1)[1].split('private fun exportLogs()',1)[0]
        icons=re.findall(r'settingsAction\(\s*R.drawable.(\w+)',settings)
        self.assertEqual(len(icons),5);self.assertEqual(len(set(icons)),5)
        self.assertNotIn('"刷新率" to',settings);self.assertNotIn('chunked(',settings)
        self.assertIn('if (schedulerError != "ok")',settings)
        record=app('PerformanceRecordActivity.kt')
        self.assertEqual(record.count('ScrollView(this)'),1)
        self.assertIn('isVerticalScrollBarEnabled = false',record)
        primary=main.split('private fun showPerformanceMonitor()',1)[1].split('private fun showMonitorHelp()',1)[0]
        self.assertNotIn('2 秒',primary)
        for title in ('性能监视器','悬浮窗权限','快捷通知','帮助','完成'):
            self.assertIn(title,primary)
        self.assertIn('showMonitorHelp()',primary)

    def test_notification_visual_controls_not_a_sampler(self):
        layout=read('app/src/main/res/layout/notification_zuicontrol.xml');quick=app('ZuiControlQuickService.kt')
        self.assertEqual(layout.count('@+id/mode_track'),1)
        self.assertEqual(layout.count('style="@style/NotificationMode"'),4)
        self.assertEqual(layout.count('android:visibility="invisible"'),4)
        self.assertEqual(layout.count('style="@style/NotificationModeDot"'),4)
        self.assertNotIn('<SeekBar',layout)
        for forbidden in ('●','○','value.title.first()', 'BigContentView','Timer','while (','/proc/','getRunningTasks','SharedPreferences'):
            self.assertNotIn(forbidden,quick)
        self.assertIn('if (value == mode) View.VISIBLE else View.INVISIBLE',quick)
        self.assertIn('getColor(value.color)',quick)
        self.assertIn('setSmallIcon(R.drawable.ic_stat_zuicontrol)',quick)
        self.assertIn('notify_monitor_active else R.drawable.notify_monitor_off',quick)
        for name in ('notify_monitor_active','notify_monitor_off'):
            self.assertIn('android:shape="oval"',read('app/src/main/res/drawable/'+name+'.xml'))
        for name in ('notify_rate_normal','notify_rate_selected'):
            self.assertNotIn('<stroke',read('app/src/main/res/drawable/'+name+'.xml'))
        self.assertIn('targetSdk = 30',read('app/build.gradle.kts'))
        # No application-icon slot in app layout. System wrapper is a separate real-device gate.
        self.assertNotIn('ic_stat',layout);self.assertNotIn('app_icon',layout)

if __name__=='__main__':unittest.main(verbosity=2)
