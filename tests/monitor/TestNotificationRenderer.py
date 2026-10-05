"""V3 notion proportions/entry-point audit. Actual reapply, timing and 10-minute stability are device gates."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
RES=ROOT/'app/src/main/res'
APP=ROOT/'app/src/main/java/com/zui/zuicontrol'
A='{http://schemas.android.com/apk/res/android}'

class NotificationRenderer(unittest.TestCase):
    def test_exact_geometry_and_native_supported_views(self):
        root=ET.parse(RES/'layout/notification_quick_control.xml').getroot()
        ids={n.get(A+'id','').removeprefix('@+id/'):n for n in root.iter() if n.get(A+'id')}
        self.assertEqual((root.get(A+'layout_width'),root.get(A+'layout_height')),('match_parent','88dp'))
        self.assertEqual([root.get(A+'padding'+side) for side in ('Start','End','Top','Bottom')],['14dp','14dp','12dp','12dp'])
        self.assertTrue(all(n.tag in ('LinearLayout','FrameLayout','TextView','ImageView') for n in root.iter()))
        self.assertEqual((ids['monitor_toggle'].get(A+'layout_width'),ids['monitor_toggle'].get(A+'layout_height')),('64dp','64dp'))
        self.assertEqual(ids['monitor_icon'].get(A+'layout_width'),'64dp')
        self.assertEqual(ids['monitor_indicator'].get(A+'layout_width'),'16dp')
        self.assertEqual(ids['monitor_indicator'].get(A+'layout_marginEnd'),'1dp')
        self.assertEqual(ids['quick_controls'].get(A+'layout_width'),'0dp')
        self.assertEqual(ids['quick_controls'].get(A+'layout_weight'),'1')
        self.assertEqual(ids['quick_controls'].get(A+'layout_marginStart'),'14dp')
        self.assertEqual(ids['quick_metrics'].get(A+'layout_width'),'56dp')
        self.assertEqual(ids['quick_metrics'].get(A+'layout_marginStart'),'14dp')
        self.assertEqual(ids['notification_quiet'].get(A+'text'),'--°C')
        self.assertEqual(ids['notification_power'].get(A+'text'),'-- W')
        self.assertEqual(ids['notification_power'].get(A+'layout_marginTop'),'4dp')
        styles={s.get('name'): {i.get('name'):i.text for i in s} for s in ET.parse(RES/'values/styles.xml').getroot().findall('style')}
        style=styles['NotificationControl']
        self.assertEqual(style['android:layout_height'],'26dp')
        self.assertEqual(style['android:textSize'],'12sp')
        self.assertEqual(style['android:includeFontPadding'],'false')
        for name,labels in [('refresh_row',['60','90','120','144','165']),('uperf_row',['节能','均衡','性能','快速'])]:
            self.assertEqual(ids[name].get(A+'layout_height'),'30dp')
            self.assertEqual(ids[name].get(A+'padding'),'2dp')
            self.assertEqual([n.get(A+'text') for n in ids[name]],labels)
        self.assertEqual(ids['uperf_row'].get(A+'layout_marginTop'),'4dp')
        self.assertEqual(14+64+14+56+14+208+14,384)
        self.assertEqual(12+30+4+30+12,88)
        self.assertFalse((RES/'layout/notification_zuicontrol.xml').exists())

    def test_every_update_uses_one_fresh_renderer(self):
        quick=(APP/'ZuiControlQuickService.kt').read_text('utf8')
        helper=(APP/'NotificationQuickControlHelper.kt').read_text('utf8')
        all_source='\n'.join(p.read_text('utf8') for p in APP.glob('*.kt'))
        self.assertEqual(all_source.count('RemoteViews(packageName,'),1)
        self.assertIn('startForeground(ID, renderNotification(snapshot()))',quick)
        self.assertIn('val content = renderNotification(state)',quick)
        self.assertIn('private fun renderNotification(snapshot:',quick)
        for action in ('setTextViewText','setTextColor','setBackgroundResource','setViewVisibility','setEnabled','setContentDescription','setOnClickPendingIntent'):
            self.assertIn(action,helper)
        for marker in ('ControlsState.observe(controlsChanged)','ControlsState.snapshot','ControlsState.remove(controlsChanged)','PerformanceMonitor(this, onReading = ::acceptReading) { requestRefresh() }'):
            self.assertIn(marker,quick)
        for forbidden in ('setCustomBigContentView','setCustomHeadsUpContentView','SharedPreferences','Timer(','Thread.sleep','SystemClock.sleep'):
            self.assertNotIn(forbidden,quick+helper)
        self.assertIn('editableScenePackage',quick)
        self.assertIn('editableDisplayHz',quick)
        self.assertIn('monitorMode',quick)
        self.assertNotIn('R.drawable.notify_monitor_off',helper)
        self.assertNotIn('else Color.rgb(117, 138, 153)',helper)
        self.assertNotRegex(helper,r'private (?:var|val)\s+\w+.*RemoteViews')

    def test_off_scalar_independence_and_cost_guards(self):
        helper=(APP/'NotificationQuickControlHelper.kt').read_text('utf8')
        quick=(APP/'ZuiControlQuickService.kt').read_text('utf8')
        monitor=(APP/'PerformanceMonitor.kt').read_text('utf8')
        self.assertIn('metric(views, R.id.notification_quiet, snapshot.quietC,',helper)
        self.assertIn('metric(views, R.id.notification_power, snapshot.powerW,',helper)
        self.assertNotIn('PerformanceMonitor.command("state")',quick)
        self.assertIn('monitor?.desiredFull()',quick)
        self.assertIn('if (controlsDirty || controls == null)',quick)
        self.assertIn('if (displayed == lastRendered) return',quick)
        self.assertIn('if (shapeChanged) positionWindow("shape")',monitor)
        render=monitor[monitor.index('private fun render('):monitor.index('private inner class MonitorView')]
        self.assertEqual(render.count('Settings.canDrawOverlays(context)'),1)

    def test_colors_and_modes(self):
        expected={'notify_card':'@color/ui_surface','notify_monitor_off':'#FFFFFF',
                  'notify_monitor_active':'@color/ui_accent','notify_monitor_indicator':'@color/mode_performance',
                  'notify_rate_normal':'#00000000','notify_rate_selected':'@color/ui_accent',
                  'notify_capsule_track':'@color/ui_field'}
        for name,color in expected.items():
            shape=ET.parse(RES/('drawable/'+name+'.xml')).getroot()
            self.assertEqual(shape.find('solid').get(A+'color'),color)
        for mode,color in [(m,'@color/mode_'+m) for m in ('powersave','balance','performance','fast')]:
            shape=ET.parse(RES/('drawable/notify_mode_'+mode+'.xml')).getroot()
            self.assertEqual(shape.find('solid').get(A+'color'),color)
            self.assertEqual(shape.find('corners').get(A+'radius'),'11dp')

    def test_existing_single_callback_and_bounded_display_only_updates(self):
        quick=(APP/'ZuiControlQuickService.kt').read_text('utf8')
        monitor=(APP/'PerformanceMonitor.kt').read_text('utf8')
        helper=(APP/'NotificationQuickControlHelper.kt').read_text('utf8')
        self.assertEqual(monitor.count('monitor("register", "", false, false, callback)'),1)
        self.assertNotIn('monitor("register"',quick)
        for forbidden in ('BatteryManager','thermal_zone','readTasks','MonitorStore','Runtime.getRuntime'):
            self.assertNotIn(forbidden,quick+helper)
        for required in ('value.isFinite()', 'value > 0.0', '"--"', 'RelativeSizeSpan', 'ForegroundColorSpan', 'Locale.US'):
            self.assertIn(required,helper)
        self.assertIn('snapshot.isFloatActive',helper)
        self.assertIn('ttl.coerceIn(3500L, 7500L)',quick)
        self.assertIn('0..readingTtl',quick)
        self.assertLess(monitor.index('onReading(next.optDouble'),monitor.index('if (!next.optBoolean("active")'))
        self.assertIn('editableSceneIsHome',quick)
        self.assertIn('if(client==backendGeneration)',monitor)
        self.assertIn('next.optString("producerEpoch")!=expectedProducer',monitor)
        self.assertIn('clickAction=action;performClick()',monitor)
        self.assertIn('handler.removeCallbacks(readingExpiry)',quick)
        self.assertIn('lastReadingPublish + 2000L',quick)
        self.assertIn('handler.removeCallbacksAndMessages(null)',quick)

if __name__=='__main__': unittest.main(verbosity=2)
