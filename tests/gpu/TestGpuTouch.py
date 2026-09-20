"""Execute exact production touch methods with inert Android shims after Gradle.

Uses Gradle's downloaded Kotlin compiler; no device, APK, or extra library.
"""
from pathlib import Path
import hashlib, os, shutil, subprocess, tempfile

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT/'app/src/main/java/com/zui/zuicontrol'
source = (APP/'GpuRangeBar.kt').read_text(encoding='utf8')

def between(start, end):
    return source[source.index(start):source.index(end, source.index(start))]

body = between('    private var minimumThumb', '    init {')
body += between('    private fun preview(', '    override fun onTouchEvent(')
body += between('    override fun onTouchEvent(', '    override fun onKeyDown(')
prefix = '''package com.zui.zuicontrol
import kotlin.math.abs
class MotionEvent(val actionMasked: Int, val x: Float, val y: Float) {
    companion object { const val ACTION_DOWN=0; const val ACTION_UP=1
        const val ACTION_MOVE=2; const val ACTION_CANCEL=3; const val ACTION_POINTER_DOWN=5 }
}
object ViewConfiguration { fun get(context: Int)=this; val scaledTouchSlop=8 }
object AccessibilityEvent { const val TYPE_VIEW_SELECTED=4 }
class HostParent { var captured=false; fun requestDisallowInterceptTouchEvent(value: Boolean) { captured=value } }
open class HostView {
    var isEnabled=true; val context=0; val parent: HostParent? = HostParent()
    fun requestFocus() {}
    open fun performClick() = true
    fun sendAccessibilityEvent(event: Int) {}
    fun postInvalidateOnAnimation() {}
    open fun onTouchEvent(event: MotionEvent) = false
}
class UnderTest(initial: GpuRanges.Range) : HostView() {
    private var currentRange=initial
    var range: GpuRanges.Range
        get()=currentRange
        set(v) { currentRange=v }
    var commits=0; var onCommit: (GpuRanges.Range)->Unit = { commits++ }
    private val unit=1f
    private val trackCenterY=20f
    private fun track()=GpuRanges.Track(0f,1100f)
    private fun x(mhz:Int)=track().x(mhz)
    private fun describe() {}
    fun keyboard(delta:Int)=step(delta)
'''
tests = '''
fun main() {
    fun bar()=UnderTest(GpuRanges.Range(629,903))
    fun send(b:UnderTest,a:Int,x:Float,y:Float=20f)=b.onTouchEvent(MotionEvent(a,x,y))
    fun unchanged(b:UnderTest) { check(b.range==GpuRanges.Range(629,903)); check(b.commits==0) }
    // Label-area taps are ignored from DOWN onward.
    for (y in listOf(41f,88f)) {
        val b=bar(); check(!send(b,0,500f,y)); check(!send(b,1,500f,y)); unchanged(b)
    }
    // Track taps and slight horizontal jitter do not jump to another OPP.
    for (dx in listOf(0f,7f,8f)) {
        val b=bar(); send(b,0,500f); send(b,2,500f+dx); send(b,1,500f+dx); unchanged(b)
        check(b.parent?.captured==false)
    }
    // Vertical gestures never commit even if the parent does not intercept.
    for (dx in listOf(0f,10f)) {
        val b=bar(); send(b,0,500f,10f); check(!send(b,2,500f+dx,45f))
        check(!send(b,1,500f+dx,88f)); unchanged(b); check(b.parent?.captured==false)
    }
    // Repeated MOVE only previews; one final commit, and duplicate UP is ignored.
    run {
        val b=bar(); send(b,0,600f)
        for (x in listOf(570f,700f,400f,610f,500f)) { send(b,2,x); check(b.commits==0) }
        check(b.parent?.captured==true); send(b,1,500f)
        check(b.range==GpuRanges.Range(578,903)); check(b.commits==1)
        check(!send(b,1,500f)); check(b.commits==1); check(b.parent?.captured==false)
    }
    // CANCEL/multitouch rolls back; a drag returning to its start does not save.
    for (finish in listOf(3,5)) {
        val b=bar(); send(b,0,600f); send(b,2,500f); send(b,finish,500f)
        send(b,1,500f); unchanged(b); check(b.parent?.captured==false)
    }
    run { val b=bar(); send(b,0,600f); send(b,2,500f); send(b,1,600f); unchanged(b) }
    // The maximum thumb, clamping and the existing keyboard path still work.
    run { val b=bar(); send(b,0,1100f); send(b,2,900f); send(b,1,900f)
        check(b.range==GpuRanges.Range(629,770)); check(b.commits==1) }
    run { val b=bar(); send(b,0,600f); send(b,2,1200f); send(b,1,1200f)
        check(b.range==GpuRanges.Range(903,903)); check(b.commits==1) }
    run { val b=bar(); b.keyboard(-1); check(b.range==GpuRanges.Range(578,903)); check(b.commits==1) }
    println("GPU_TOUCH_REGRESSION=PASS; MOVE_COMMITS=0; VERTICAL_BLANK_TAP_COMMITS=0; COMPLETED_DRAG_COMMITS=1")
}
'''
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home()/'.gradle'))/'caches/modules-2/files-2.1'
compilers = list((cache/'org.jetbrains.kotlin/kotlin-compiler-embeddable').glob('*/*/*.jar'))
assert len(compilers)==1, 'Run Gradle first; expected one resolved Kotlin compiler: '+str(compilers)
version = compilers[0].parent.parent.name
jars = list((cache/'org.jetbrains.kotlin').glob('*/'+version+'/*/*.jar'))
jars += list((cache/'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm').glob('*/*/*.jar'))
annotations = list((cache/'org.jetbrains/annotations/13.0').glob('*/*.jar'))
jars += annotations
stdlib = next((cache/'org.jetbrains.kotlin/kotlin-stdlib'/version).glob('*/*.jar'))
java = str(Path(os.environ['JAVA_HOME'])/'bin'/('java.exe' if os.name=='nt' else 'java')) if 'JAVA_HOME' in os.environ else shutil.which('java')
with tempfile.TemporaryDirectory(prefix='zuicontrol-gpu-touch-') as temp:
    temp=Path(temp); harness=temp/'GpuTouchTest.kt'
    harness.write_text(prefix+body+'}\n'+tests,encoding='utf8')
    subprocess.run([java,'-cp',os.pathsep.join(map(str,jars)),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect',
        '-classpath',os.pathsep.join(map(str,[stdlib,*annotations])), '-d',str(temp/'classes'),
        str(harness),str(APP/'GpuRanges.kt'),str(APP/'PackageNames.kt')],check=True,timeout=60)
    subprocess.run([java,'-cp',os.pathsep.join(map(str,[temp/'classes',stdlib])),
        'com.zui.zuicontrol.GpuTouchTestKt'],check=True,timeout=20)
print('PRODUCTION_TOUCH_SOURCE_SHA256='+hashlib.sha256((APP/'GpuRangeBar.kt').read_bytes()).hexdigest())
