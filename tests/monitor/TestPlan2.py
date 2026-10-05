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
 def test_many_competition_groups_scroll_instead_of_compressing(self):
  main=(APP/'MainActivity.kt').read_text(encoding='utf8')
  self.assertIn('FrameLayout.LayoutParams(dp(classes.size*52),dp(34))',main)
  self.assertNotIn('minOf(340,classes.size*52)',main)
 def test_charts_keep_gap_semantics(self):
  chart=read('RecordChart.kt')
  self.assertIn('!value.isFinite() || value < 0',chart);self.assertIn('t-previous>gapLimit',chart)
  self.assertIn('LinearGradient',chart);self.assertIn('RadialGradient',chart)
  self.assertNotIn('cubicTo',chart);self.assertNotIn('quadTo',chart)
if __name__=='__main__':unittest.main(verbosity=2)
