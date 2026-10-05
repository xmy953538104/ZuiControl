"""Plan2 UI ownership checks; actual pixels, input and latency require device evidence."""
from pathlib import Path
import unittest
APP=Path(__file__).resolve().parents[2]/'app/src/main/java/com/zui/zuicontrol'
def read(name):return (APP/name).read_text(encoding='utf8')
class Plan2(unittest.TestCase):
 def test_theme_is_local_and_non_intercepting(self):
  main=read('MainActivity.kt');theme=main.split('private fun theme(theme: String)',1)[1].split('private fun monitorGuidance',1)[0]
  for forbidden in ('recreate','load()','gateway','ZuiControlRequest','isClickable=true'):self.assertNotIn(forbidden,theme)
  self.assertIn('dispatchTouchEvent(event:MotionEvent)=false',theme);self.assertIn('setDuration(275)',theme)
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
  self.assertIn('if(snapshot==null || session.section=="thread")runCatching',main)
  self.assertIn('actionRow("规则集导出"',main);self.assertIn('规则源尚未启用',main)
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
  self.assertIn('physicalHost.addView(fade',main)
  self.assertNotIn('ownerHost.addView(fade',main)
  self.assertIn('weight,false)',ui);self.assertNotIn('if(weight==400)',ui)
  identity=ui.split('fun identityTitle',1)[1].split('fun section',1)[0]
  self.assertIn('maxLines=2;ellipsize=null',identity)
  self.assertIn('owner.identityTitle(name(d.packageName)',main)
  self.assertIn('owner.identityTitle(name(pkg)',main)
 def test_inline_cpu_has_bounded_existing_authority(self):
  spark=read('OwnerCpuSparklineView.kt')
  self.assertIn('targets.take(15)',spark);self.assertIn('newSingleThreadExecutor',spark)
  self.assertIn('put("thread",key)',spark);self.assertIn('data.optLong("recordId")==id',spark)
  self.assertIn('Paint.Style.STROKE',spark);self.assertNotIn('Paint.Style.FILL',spark)
  for name in ('MainActivity.kt','PerformanceRecordActivity.kt'):self.assertIn('owner.cpuThreadTable(',read(name))
  self.assertNotIn('"查看 ›"',read('MainActivity.kt'))
if __name__=='__main__':unittest.main(verbosity=2)
