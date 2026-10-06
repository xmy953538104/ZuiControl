"""Plan2 UI ownership checks; actual pixels, input and latency require device evidence."""
from pathlib import Path
import unittest
APP=Path(__file__).resolve().parents[2]/'app/src/main/java/com/zui/zuicontrol'
def read(name):return (APP/name).read_text(encoding='utf8')
class Plan2(unittest.TestCase):
 def test_settings_theme_selection_follows_preference_on_same_palette(self):
  main=read('MainActivity.kt')
  settings=main.split('private fun settingsPage()',1)[1].split('private var themeFramePending',1)[0]
  theme=main.split('private fun theme(theme: String)',1)[1].split('private fun monitorGuidance',1)[0]
  self.assertEqual(settings.count('val themeSegment=segment('),1)
  self.assertIn('bindPresentation{themeSegment.showSelection(values.indexOf(prefs.getString("theme","system")))}',settings)
  pref=theme.index('prefs.edit().putString("theme",theme).apply()')
  rebind=theme.index('pageBindings.toList().forEach{it()}')
  self.assertLess(pref,rebind)
  self.assertLess(rebind,theme.index('if(themeFramePending)return'))
  self.assertLess(rebind,theme.index('if(dark==owner.dark)'))
  for forbidden in ('render()', 'removeAllViews', 'recreate(', 'gateway', 'ZuiControlRequest'):
   self.assertNotIn(forbidden,theme)
 def test_status_theme_does_not_reconfigure_window(self):
  main=read('MainActivity.kt');window=read('OwnerWindow.kt')
  theme=main.split('private fun theme(theme: String)',1)[1].split('private fun monitorGuidance',1)[0]
  appearance=window.split('fun updateSystemBarAppearance',1)[1].split('/** Drawable overlay',1)[0]
  self.assertEqual(main.count('OwnerWindow.fullscreen(this)'),1)
  self.assertIn('ownerCanvas.postOnAnimation',theme)
  self.assertIn('OwnerWindow.transitionSystemBars(this,physicalHost,rail,master',theme)
  for forbidden in ('fullscreen(', 'requestApplyInsets', 'setContentView', 'recreate(', 'setDecorFitsSystemWindows', 'show(WindowInsets', 'LAYOUT_', 'statusBarColor=', 'navigationBarColor='):
   self.assertNotIn(forbidden,theme)
  for forbidden in ('setDecorFitsSystemWindows','show(','LAYOUT_','requestApplyInsets','statusBarColor','navigationBarColor'):
   self.assertNotIn(forbidden,appearance)
  bridge=window.split('fun transitionSystemBars',1)[1].split('fun dark(',1)[0]
  self.assertIn('WindowInsets.Type.statusBars()',bridge)
  self.assertIn('bridge.setBounds(0,0,host.width,top)',bridge)
  self.assertIn('host.overlay.remove(bridge)',bridge)
  self.assertIn('ms>inkChangedAt',bridge)
  self.assertIn('registerFrameCommitCallback',bridge)
  self.assertLess(bridge.index('updateSystemBarAppearance(activity,dark)'),bridge.index('frame(animation.animatedFraction,colors)'))
  for forbidden in ('addView(', 'setContentView', 'requestApplyInsets', 'setPadding', 'layoutParams', 'onTouch'):
   self.assertNotIn(forbidden,bridge)
 def test_theme_is_local_and_non_intercepting(self):
  main=read('MainActivity.kt');theme=main.split('private fun theme(theme: String)',1)[1].split('private fun monitorGuidance',1)[0]
  for forbidden in ('recreate','load()','gateway','ZuiControlRequest','isClickable=true'):self.assertNotIn(forbidden,theme)
  self.assertIn('owner.applyTheme(physicalHost',theme)
  for forbidden in ('render()', 'removeAllViews', 'addView(', 'OwnerUi(this)'):self.assertNotIn(forbidden,theme)
 def test_system_bars_icons_and_local_draft(self):
  window=read('OwnerWindow.kt');main=read('MainActivity.kt')
  for flag in ('SYSTEM_UI_FLAG_FULLSCREEN','SYSTEM_UI_FLAG_HIDE_NAVIGATION','SYSTEM_UI_FLAG_IMMERSIVE_STICKY','hide(WindowInsets'):self.assertNotIn(flag,window+main)
  self.assertIn('show(WindowInsets.Type.systemBars())',window)
  icon=main.split('private fun ownerAppIcon',1)[1].split('private fun ownerListRow',1)[0]
  self.assertNotIn('background=',icon);self.assertNotIn('foreground=',icon)
  draft=main.split('private fun appPage()',1)[1].split('private fun ownerReadout',1)[0]
  for forbidden in ('setGlobal(', 'setOverlay(', 'sendPolicy(', 'session.busy && configurable'):self.assertNotIn(forbidden,draft)
  self.assertNotIn('localRender()',draft);self.assertIn('policySelector.showSelection',draft)
  self.assertIn('addView(policySelector',draft);self.assertIn('addView(bar,LinearLayout.LayoutParams(0',draft)
 def test_thread_ia_and_notification_conditional(self):
  main=read('MainActivity.kt')
  self.assertNotIn('listRow("","规则库"',main);self.assertNotIn('owner.label("职责边界"',main)
  self.assertIn('"thread"->{loadInventory();loadRules()}',main)
  self.assertIn('snapshot?.generation==generation && baseline?.generation==generation',main)
  self.assertIn('owner.domainRow("规则集导出"',main);self.assertIn('规则源尚未启用',main)
  self.assertIn('if(!enabled)addView(owner.button("系统通知设置"',main)
 def test_charts_keep_gap_semantics(self):
  chart=read('RecordChart.kt')
  self.assertIn('!value.isFinite() || value < 0',chart);self.assertIn('t-previous>gapLimit',chart)
  self.assertIn('LinearGradient',chart);self.assertIn('RadialGradient',chart)
  self.assertNotIn('cubicTo',chart);self.assertNotIn('quadTo',chart)
 def test_visual_closure_composition_and_exact_type(self):
  window=read('OwnerWindow.kt');ui=read('OwnerUi.kt');main=read('MainActivity.kt')
  self.assertIn('v.setPadding(0,0,0,0)',window)
  self.assertNotIn('v.setPadding(bars.left',window)
  self.assertIn('val dh=(650*unit).roundToInt()',ui)
  self.assertNotIn('physicalHost.removeAllViews',main)
  self.assertIn('private fun createShell()',main)
  render=main.split('private fun render()',1)[1].split('private fun buildMaster',1)[0]
  self.assertNotIn('OwnerDesignLayout(',render)
  self.assertNotIn('setContentView',render)
  self.assertIn('weight,false)',ui);self.assertNotIn('if(weight==400)',ui)
  identity=ui.split('fun identityTitle',1)[1].split('fun section',1)[0]
  self.assertIn('maxLines=2;ellipsize=null',identity)
  self.assertIn('owner.identityTitle(name(d.packageName)',main)
  self.assertIn('owner.identityTitle(name(pkg)',main)
  self.assertIn('},LinearLayout.LayoutParams(0,-2,1f))',identity)
 def test_owner_subpage_navigation_and_explicit_icons(self):
  main=read('MainActivity.kt');perf=read('PerformanceRecordActivity.kt')
  self.assertNotIn('‹ 返回记录详情',main);self.assertNotIn('owner.button("返回"',perf)
  self.assertNotIn('title.contains("线程")',main);self.assertNotIn('title.contains("规则")',main)
  self.assertIn('R.drawable.owner_thread_list',main)
  self.assertIn('R.drawable.owner_appopt',main);self.assertIn('R.drawable.owner_undo',main)
  self.assertIn('selectedThread=data.put("key",key)',main)
 def test_persistent_masks_and_foreground_draft_safety(self):
  picker=read('OwnerCpuPicker.kt');foreground=read('FrontendForeground.kt');session=read('FrontendSession.kt')
  self.assertNotIn('removeAllViews',picker)
  self.assertIn('onActivityStopped',foreground);self.assertNotIn('onActivityPaused(activity:Activity){',foreground)
  home=session.split('fun returnHome()',1)[1].split('fun hasDraftFor',1)[0]
  for forbidden in ('clearDrafts','gateway','appDraft=null','ruleDraft=null'):self.assertNotIn(forbidden,home)
 def test_inline_cpu_has_bounded_existing_authority(self):
  spark=read('OwnerCpuSparklineView.kt')
  self.assertIn('targets.take(15)',spark);self.assertIn('newSingleThreadExecutor',spark)
  self.assertIn('put("thread",key)',spark);self.assertIn('data.optLong("recordId")==id',spark)
  self.assertIn('Paint.Style.STROKE',spark);self.assertNotIn('Paint.Style.FILL',spark)
  self.assertIn('owner.cpuThreadTable(',read('MainActivity.kt'))
  self.assertIn('putExtra("openRecord",true)',read('PerformanceRecordActivity.kt'))
  self.assertNotIn('"查看 ›"',read('MainActivity.kt'))
if __name__=='__main__':unittest.main(verbosity=2)
