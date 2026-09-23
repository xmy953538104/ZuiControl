"""R10 design/entry-point audit. Actual reapply, timing and 10-minute stability are device gates."""
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
        self.assertEqual((root.get(A+'layout_width'),root.get(A+'layout_height')),('384dp','88dp'))
        self.assertEqual([root.get(A+'padding'+side) for side in ('Start','End','Top','Bottom')],['16dp']*4)
        self.assertTrue(all(n.tag in ('LinearLayout','FrameLayout','TextView','ImageView') for n in root.iter()))
        self.assertEqual((ids['monitor_toggle'].get(A+'layout_width'),ids['monitor_toggle'].get(A+'layout_height')),('56dp','56dp'))
        self.assertEqual(ids['monitor_icon'].get(A+'layout_width'),'56dp')
        self.assertEqual(ids['monitor_indicator'].get(A+'layout_width'),'10dp')
        self.assertEqual(ids['monitor_indicator'].get(A+'layout_marginEnd'),'3.5dp')
        self.assertEqual(ids['quick_controls'].get(A+'layout_width'),'222dp')
        self.assertEqual(ids['quick_controls'].get(A+'layout_marginStart'),'10dp')
        self.assertEqual(ids['quick_metrics'].get(A+'layout_width'),'52dp')
        self.assertEqual(ids['quick_metrics'].get(A+'layout_marginStart'),'12dp')
        self.assertEqual(ids['notification_quiet'].get(A+'text'),'--°C')
        self.assertEqual(ids['notification_power'].get(A+'text'),'-- W')
        self.assertEqual(ids['notification_power'].get(A+'layout_marginTop'),'4dp')
        styles={s.get('name'): {i.get('name'):i.text for i in s} for s in ET.parse(RES/'values/styles.xml').getroot().findall('style')}
        style=styles['NotificationControl']
        self.assertEqual(style['android:layout_height'],'22dp')
        self.assertEqual(style['android:textSize'],'11.5sp')
        self.assertEqual(style['android:includeFontPadding'],'false')
        for name,labels in [('refresh_row',['60','90','120','144','165']),('uperf_row',['节能','均衡','性能','极速'])]:
            self.assertEqual(ids[name].get(A+'layout_height'),'26dp')
            self.assertEqual(ids[name].get(A+'padding'),'2dp')
            self.assertEqual([n.get(A+'text') for n in ids[name]],labels)
        self.assertEqual(ids['uperf_row'].get(A+'layout_marginTop'),'4dp')
        self.assertEqual(16+56+12+52+10+222+16,384)
        self.assertEqual(16+26+4+26+16,88)
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
        for marker in ('KEY_STATUS_TEXT','KEY_UPERF_MODE','KEY_UPERF_RULES_TEXT','PerformanceMonitor(this, onReading = ::acceptReading) { requestRefresh() }'):
            self.assertIn(marker,quick)
        for forbidden in ('setCustomBigContentView','setCustomHeadsUpContentView','SharedPreferences','Timer(','Thread.sleep','SystemClock.sleep'):
            self.assertNotIn(forbidden,quick+helper)
        self.assertIn('editableScenePackage',quick)
        self.assertIn('editableDisplayHz',quick)
        self.assertIn('monitorMode',quick)
        self.assertNotIn('R.drawable.notify_monitor_off',helper)
        self.assertNotIn('else Color.rgb(117, 138, 153)',helper)
        self.assertNotRegex(helper,r'private (?:var|val)\s+\w+.*RemoteViews')

    def test_colors_and_modes(self):
        expected={'notify_card':'#F4F6FA','notify_monitor_off':'#FFFFFF',
                  'notify_monitor_active':'#3B67C1','notify_monitor_indicator':'#F59E0B',
                  'notify_rate_normal':'#00000000','notify_rate_selected':'#3B67C1',
                  'notify_capsule_track':'#E2E7EE'}
        for name,color in expected.items():
            shape=ET.parse(RES/('drawable/'+name+'.xml')).getroot()
            self.assertEqual(shape.find('solid').get(A+'color'),color)
        for mode,color in [('powersave','#15A05C'),('balance','#3B67C1'),('performance','#EA580C'),('fast','#D92424')]:
            shape=ET.parse(RES/('drawable/notify_mode_'+mode+'.xml')).getroot()
            self.assertEqual(shape.find('solid').get(A+'color'),color)
            self.assertEqual(shape.find('corners').get(A+'radius'),'11dp')

    def test_existing_single_callback_and_bounded_display_only_updates(self):
        quick=(APP/'ZuiControlQuickService.kt').read_text('utf8')
        monitor=(APP/'PerformanceMonitor.kt').read_text('utf8')
        helper=(APP/'NotificationQuickControlHelper.kt').read_text('utf8')
        self.assertEqual(monitor.count('false, false, callback)'),1)
        self.assertNotIn('monitor("register"',quick)
        for forbidden in ('BatteryManager','thermal_zone','readTasks','MonitorStore','Runtime.getRuntime'):
            self.assertNotIn(forbidden,quick+helper)
        for required in ('value.isFinite()', 'value > 0.0', '"--"', 'RelativeSizeSpan', 'ForegroundColorSpan', 'Locale.US'):
            self.assertIn(required,helper)
        self.assertIn('snapshot.isFloatActive',helper)
        self.assertIn('0..3500L',quick)
        self.assertIn('handler.removeCallbacks(readingExpiry)',quick)
        self.assertIn('lastReadingPublish + 2000L',quick)
        self.assertIn('handler.removeCallbacksAndMessages(null)',quick)

if __name__=='__main__': unittest.main(verbosity=2)
