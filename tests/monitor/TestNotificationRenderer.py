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
        self.assertEqual([root.get(A+'padding'+side) for side in ('Start','End','Top','Bottom')],['14dp','14dp','11dp','11dp'])
        self.assertTrue(all(n.tag in ('LinearLayout','FrameLayout','TextView','ImageView') for n in root.iter()))
        self.assertEqual((ids['monitor_toggle'].get(A+'layout_width'),ids['monitor_toggle'].get(A+'layout_height')),('44dp','44dp'))
        self.assertEqual(ids['monitor_toggle'].get(A+'layout_gravity'),'center_vertical')
        self.assertEqual(ids['monitor_icon'].get(A+'layout_width'),'22dp')
        self.assertEqual(ids['monitor_indicator'].get(A+'layout_width'),'6dp')
        self.assertEqual(ids['monitor_indicator'].get(A+'layout_margin'),'3dp')
        self.assertEqual(ids['quick_controls'].get(A+'layout_marginStart'),'14dp')
        self.assertEqual(ids['quick_controls'].get(A+'layout_weight'),'1')
        self.assertEqual(ids['uperf_row'].get(A+'layout_marginTop'),'10dp')
        styles={s.get('name'): {i.get('name'):i.text for i in s} for s in ET.parse(RES/'values/styles.xml').getroot().findall('style')}
        style=styles['NotificationControl']
        self.assertEqual({k:style[k] for k in ('android:layout_width','android:layout_height','android:layout_weight','android:fontFamily','android:textSize')},
                         {'android:layout_width':'0dp','android:layout_height':'28dp','android:layout_weight':'1','android:fontFamily':'sans-serif','android:textSize':'12.5sp'})
        for name,labels,gap,width in [('refresh_row',['60','90','120','144','165'],5,55.6),('uperf_row',['节能','均衡','性能','极速'],8,68.5)]:
            row=ids[name]
            self.assertEqual(row.get(A+'layout_height'),'28dp')
            self.assertEqual([n.get(A+'text') for n in row],labels)
            self.assertEqual([n.get(A+'layout_marginStart','0dp') for n in row],['0dp']+[str(gap)+'dp']*(len(labels)-1))
            self.assertEqual((384-28-44-14-gap*(len(labels)-1))/len(labels),width)
        self.assertEqual(11+28+10+28+11,88)
        self.assertEqual(style['android:textStyle'],'bold')
        self.assertEqual(style['android:includeFontPadding'],'false')
        self.assertTrue(all(n.get(A+'layout_marginEnd','0dp')=='0dp' for n in (ids['refresh_165'],ids['mode_fast'])))
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
        for marker in ('KEY_STATUS_TEXT','KEY_UPERF_MODE','KEY_UPERF_RULES_TEXT','PerformanceMonitor(this) { requestRefresh() }'):
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
        expected={'notify_card':'#F2F4F7','notify_monitor_off':'#FFFFFF',
                  'notify_monitor_active':'#3C69C0','notify_monitor_indicator':'#FFB300',
                  'notify_rate_normal':'#FFFFFF','notify_rate_selected':'#3C69C0'}
        for name,color in expected.items():
            shape=ET.parse(RES/('drawable/'+name+'.xml')).getroot()
            self.assertEqual(shape.find('solid').get(A+'color'),color)
        for mode,color in [('powersave','#538C35'),('balance','#4774CE'),('performance','#CA6B21'),('fast','#D64E70')]:
            shape=ET.parse(RES/('drawable/notify_mode_'+mode+'.xml')).getroot()
            self.assertEqual(shape.find('solid').get(A+'color'),'@color/mode_'+mode)
            self.assertEqual(shape.find('corners').get(A+'radius'),'8dp')
            tokens={c.get('name'):c.text for c in ET.parse(RES/'values/ui_tokens.xml').getroot().findall('color')}
            self.assertEqual(tokens['mode_'+mode],color)

if __name__=='__main__': unittest.main(verbosity=2)
