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
        self.assertIn('segment(classes.mapIndexed',main)
        self.assertIn('classes.indexOf(cls)){cls=classes[it]}',main)
        self.assertNotIn('picker.setSelection(',main)
        compact=re.sub(r'\s+','',main)
        self.assertIn('UperfAppPolicy.isConfigurable(packageManager,pkg)',compact)
        self.assertIn('fetch={UperfAppPolicy.isConfigurable',compact)
        self.assertIn('owner.tiers(d.uperfMode,true,configurable,reconcile=',compact)
        self.assertIn('isEnabled=configurable&&custom',compact)
        self.assertIn('valcustom=d.gpuPolicy==ZuiControlClient.GpuPolicy.CUSTOM',compact)
        self.assertIn('editableSceneIsHome',quick)
        self.assertIn('|| UperfAppPolicy.isConfigurable(packageManager, pkg)',quick)
        self.assertIn('snapshot.uperfEnabled, modeIntent(value)',app('NotificationQuickControlHelper.kt'))
        self.assertIn('UperfAppPolicy.isConfigurable(packageManager, pkg)',quick)

    def test_shared_policy_and_bounded_queries(self):
        policy=app('UperfAppPolicy.kt');daemon=read('payload/system/bin/zui_controld')
        roots=re.search(r'allowedRoots = listOf\((.*?)\)',policy)[1]
        self.assertEqual(re.findall('"([^"]+)"',roots),['/data/app/','/system/preinstall/'])
        roots_case=re.search(r'case "\$1" in (/data/app/.*?)\) return 0',daemon)[1]
        self.assertEqual(roots_case,'/data/app/?*.apk|/system/preinstall/?*.apk')
        for name in ('android','com.android.systemui','com.zui.zuicontrol'):
            self.assertIn('"'+name+'"',policy)
        self.assertIn('android|com.android.systemui) return 1',daemon)
        self.assertIn('qualified_system_scene=1',daemon)
        self.assertIn('android.intent.category.HOME',daemon)
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
        for token in ('mScreenInteractive','!locked','eligible'):
            self.assertIn(token,monitor)
        self.assertEqual(monitor.count('lock.isKeyguardLocked()'),1)
        overlay=app('PerformanceMonitor.kt')
        for token in ('WindowInsets.Type.statusBars()','WindowInsets.Type.displayCutout()',
                      'WindowInsets.Type.captionBar()','setFitInsetsTypes(0)','setOnApplyWindowInsetsListener',
                      'Gravity.TOP or if (circle) Gravity.LEFT else Gravity.CENTER_HORIZONTAL'):
            self.assertIn(token,overlay)
        self.assertNotIn('com.zui.launcher',overlay+monitor)
        session=read('framework_patch/src/services/com/zui/server/control/MonitorSession.java')
        self.assertNotIn('mode = 1;',session)

    def test_owner_ui(self):
        main=app('MainActivity.kt');ui=app('UiControls.kt')
        # Latest Owner V3: four settings modules; Health is exclusively in Dashboard.
        settings=main.split('private fun settingsPage()',1)[1].split('private fun theme(',1)[0]+main.split('private fun gpuDefaultsPage()',1)[1].split('private fun saveDraft(',1)[0]
        for title in ('监测与显示','GPU 默认范围','数据与维护','关于','立即备份','从备份恢复','恢复出厂配置','导出运行日志','重启调度核心'):
            self.assertIn(title,settings)
        self.assertNotIn('BackendHealth',settings)
        self.assertIn('owner.chip(if(enabled)"已开启" else "已关闭"',settings)
        self.assertIn('Settings.ACTION_APP_NOTIFICATION_SETTINGS',settings)
        self.assertIn('Settings.EXTRA_APP_PACKAGE,packageName',settings)
        self.assertIn('owner.settingsRow("界面主题"',settings)
        self.assertIn('LinearLayout.LayoutParams(dp(220),dp(34))',settings)
        self.assertIn('listOf("跟随系统", "深色", "浅色")',settings)
        self.assertNotIn('职责',settings)
        self.assertIn('private fun coreHealth()',main)
        self.assertIn('暂无可用于线程分析的监测记录',main)
        self.assertIn('停止',main)
        record=app('PerformanceRecordActivity.kt')
        self.assertIn('Intent(this,MainActivity::class.java)',record)
        self.assertIn('CPU 时间线',main);self.assertIn('入榜期间单核百分比',main)
        self.assertIn('isVerticalScrollBarEnabled=false',main)

    def test_notification_visual_controls_not_a_sampler(self):
        # R10 Owner replaces the historical track/dot visuals; retained controller boundaries still apply.
        layout=read('app/src/main/res/layout/notification_quick_control.xml');quick=app('ZuiControlQuickService.kt')
        renderer=app('NotificationQuickControlHelper.kt')
        self.assertNotIn('mode_track',layout)
        self.assertEqual(layout.count('style="@style/NotificationControl"'),9)
        self.assertNotIn('<SeekBar',layout)
        for forbidden in ('●','○','value.title.first()', 'BigContentView','Timer','while (','/proc/','getRunningTasks','SharedPreferences'):
            self.assertNotIn(forbidden,quick)
        self.assertIn('value == snapshot.currentMode',renderer)
        for mode in ('powersave','balance','performance','fast'):
            self.assertIn('R.drawable.notify_mode_'+mode,renderer)
        self.assertIn('setSmallIcon(R.drawable.ic_stat_zuicontrol)',quick)
        self.assertIn('R.drawable.notify_monitor_active',renderer)
        self.assertNotIn('R.drawable.notify_monitor_off',renderer)
        for name in ('notify_monitor_active','notify_monitor_off'):
            self.assertIn('android:shape="oval"',read('app/src/main/res/drawable/'+name+'.xml'))
        self.assertIn('android:color="#00000000"',read('app/src/main/res/drawable/notify_rate_normal.xml'))
        self.assertIn('targetSdk = 30',read('app/build.gradle.kts'))
        # No application-icon slot in app layout. System wrapper is a separate real-device gate.
        self.assertNotIn('ic_stat',layout);self.assertNotIn('app_icon',layout)

if __name__=='__main__':unittest.main(verbosity=2)
