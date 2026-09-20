"""Production source reachability/layout contracts; actual rendering is a device gate."""
from pathlib import Path
import re, unittest, xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT/'app/src/main/java/com/zui/zuicontrol'
def source(name): return (APP/name).read_text(encoding='utf8')

class UiPolish(unittest.TestCase):
    def test_move_returns_before_commit_and_preview_is_local(self):
        text = source('GpuRangeBar.kt')
        move = text.split('MotionEvent.ACTION_MOVE -> {', 1)[1].split('MotionEvent.ACTION_UP -> {', 1)[0]
        for forbidden in ('onCommit', 'onPreview', 'describe(', 'requestLayout', 'ZuiControlClient', 'range ='):
            self.assertNotIn(forbidden, move)
        self.assertIn('preview(track().snap(event.x))', move)
        preview = text.split('private fun preview(value: Int) {', 1)[1].split('override fun onTouchEvent', 1)[0]
        for forbidden in ('onCommit', 'onPreview', 'describe(', 'requestLayout', 'Thread', 'ZuiControlClient', 'range =', 'setContentDescription'):
            self.assertNotIn(forbidden, preview)
        self.assertIn('currentRange = next; postInvalidateOnAnimation()', preview)
        touch = text.split('override fun onTouchEvent', 1)[1].split('override fun performClick', 1)[0]
        self.assertEqual(touch.count('onCommit(range)'), 1)
        self.assertIn('val changed = dragging && range != beforeDrag', touch)
        self.assertIn('if (changed) onCommit(range)', touch)
        self.assertLess(touch.index('MotionEvent.ACTION_MOVE'), touch.index('onCommit(range)'))
        for forbidden in ('requestLayout', 'setGpuRange', 'setGlobalGpuRange', 'onPreview'):
            self.assertNotIn(forbidden, text)
        self.assertIn('x(231), y, x(903)', text)
        self.assertIn('track().labelsCollide', text)

    def test_shared_dropdown_chip_grid_and_tools(self):
        ui = source('UiControls.kt'); main = source('MainActivity.kt')
        self.assertIn('PopupWindow(list, width, popupHeight, true)', ui)
        self.assertIn('frame.bottom - xy[1] - height - gap', ui)
        self.assertIn('showAsDropDown(this@AnchoredDropdown, 0, gap, Gravity.START)', ui)
        self.assertIn('overlapAnchor = false', ui)
        self.assertEqual(main.count('val picker = traySpinner('), 2)
        self.assertIn('AnchoredDropdown(this, items)', main)
        self.assertNotIn('Spinner(this)', main)
        self.assertNotIn('✓', main); self.assertNotIn('性能：', main)
        self.assertNotIn('熄屏固定节能', main)
        self.assertIn('mode.title, mode == selected', main)
        self.assertIn('(contentWidthDp / 260).coerceIn(2, 4)', ui)
        self.assertEqual(main.count('addAppGrid('), 3)  # definition + both callers
        settings = main.split('private fun buildSystemPage()', 1)[1].split('private fun exportLogs()', 1)[0]
        self.assertEqual(settings.count('settingsActionMargins()'), 5)
        self.assertNotIn('spaced =', main)
        self.assertIn('addView(reset, LinearLayout.LayoutParams(-2, dimen(R.dimen.ui_chip_height))', main)
        self.assertEqual(main.count('gravity = Gravity.TOP; topMargin = bar.chipTopMargin'), 2)
        self.assertNotIn('maxOf(dp(92)', main)
        self.assertIn('"ZUIopt" to threadState, "刷新率" to "system").chunked(2)', main)

    def test_overlay_tokens_and_raw_recording_boundary(self):
        tokens = {x.attrib['name']: x.text for x in ET.parse(ROOT/'app/src/main/res/values/ui_tokens.xml').getroot()}
        self.assertEqual(tokens['monitor_bar_height'], '29dp')
        self.assertEqual(tokens['monitor_circle_diameter'], '48dp')
        self.assertEqual(tokens['monitor_touch_diameter'], '56dp')
        self.assertEqual(tokens['monitor_unit_scale'], '60%')
        self.assertTrue(0.26 <= int(tokens['monitor_glass'][1:3], 16)/255 <= 0.30)
        overlay = source('PerformanceMonitor.kt')
        self.assertIn('metricWidth(it)', overlay); self.assertIn('livePower.add(', overlay)
        self.assertNotRegex(overlay, r'\b276\b'); self.assertNotIn('circleText', overlay)
        for forbidden in ('MediaProjection', 'PixelCopy', 'SurfaceControl', 'kgsl', '/proc/', 'SQLite'):
            self.assertNotIn(forbidden, overlay)
        collector = (ROOT/'framework_patch/src/services/com/zui/server/control/MonitorCollector.java').read_text(encoding='utf8')
        self.assertIn('store.append(now,fps,power,quiet,', collector)
        self.assertNotIn('LivePower', collector)
        for field in ('batteryStatus', 'batteryPlugged', 'batteryVoltageMv', 'batteryCurrentUa', 'batteryCurrentMagnitudeA'):
            self.assertIn('.put("'+field+'",', collector)

if __name__ == '__main__': unittest.main(verbosity=2)
