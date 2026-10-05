"""Execute the production commit and nested listener, including ListView receiver shadowing."""
from pathlib import Path
import hashlib,os,shutil,subprocess,tempfile,re
ROOT=Path(__file__).resolve().parents[2];APP=ROOT/'app/src/main/java/com/zui/zuicontrol'
ui=(APP/'UiControls.kt').read_text(encoding='utf8');main=(APP/'MainActivity.kt').read_text(encoding='utf8')
commit=ui[ui.index('    fun commitSelection('):ui.index('    private fun showChoices(')]
listener=re.search(r'            setOnItemClickListener \{.*?\n            }',ui,re.S)[0]
# Current Owner form uses segmented classes; keep the production dropdown
# listener regression below without requiring the superseded form composition.
assert 'classes.indexOf(cls)){cls=classes[it]}' in main
prefix='''package com.zui.zuicontrol
class HostText { var text="" }
class PopupWindow { var dismissals=0; fun dismiss(){dismissals++} }
class ListView {
    var platformSelection=-1
    private var click: (Any?,Any?,Int,Long)->Unit={_,_,_,_->}
    fun setSelection(index:Int){platformSelection=index}
    fun setOnItemClickListener(value:(Any?,Any?,Int,Long)->Unit){click=value}
    fun click(index:Int){click(null,null,index,index.toLong())}
}
class AnchoredDropdown(private val items:List<String>) {
    var selectedItemPosition=0; private set
    var onSelection:(Int)->Unit={}
    val selectedText=HostText(); var contentDescription=""
    private val popup:PopupWindow?=PopupWindow()
    init {commitSelection(0)}
'''
harness=prefix+commit+'\n    fun choices()=ListView().apply {\n'+listener+'\n    }\n}\n'+'''
fun main() {
    val picker=AnchoredDropdown(listOf("A","B","C","New"))
    val list=picker.choices(); val callbacks=mutableListOf<Int>()
    picker.onSelection={callbacks.add(it)}
    for(index in listOf(1,3,0,2)) {
        list.click(index)
        check(picker.selectedItemPosition==index)
        check(picker.selectedText.text==listOf("A","B","C","New")[index])
        check(callbacks.last()==index);check(list.platformSelection==-1)
    }
    println("DROPDOWN_CLASS_SELECTION=PASS; PLATFORM_SELECTION_CALLS=0")
}
'''
cache=Path(os.environ.get('GRADLE_USER_HOME',Path.home()/'.gradle'))/'caches/modules-2/files-2.1'
compilers=list((cache/'org.jetbrains.kotlin/kotlin-compiler-embeddable').glob('*/*/*.jar'))
assert len(compilers)==1,'Run Gradle first: '+str(compilers)
version=compilers[0].parent.parent.name
jars=list((cache/'org.jetbrains.kotlin').glob('*/'+version+'/*/*.jar'))
jars+=list((cache/'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm').glob('*/*/*.jar'))
annotations=list((cache/'org.jetbrains/annotations/13.0').glob('*/*.jar'));jars+=annotations
stdlib=next((cache/'org.jetbrains.kotlin/kotlin-stdlib'/version).glob('*/*.jar'))
java=str(Path(os.environ['JAVA_HOME'])/'bin'/('java.exe' if os.name=='nt' else 'java')) if 'JAVA_HOME' in os.environ else shutil.which('java')
with tempfile.TemporaryDirectory(prefix='zui-dropdown-') as tmp:
    tmp=Path(tmp);src=tmp/'DropdownTest.kt';src.write_text(harness,encoding='utf8')
    subprocess.run([java,'-cp',os.pathsep.join(map(str,jars)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib','-no-reflect','-classpath',os.pathsep.join(map(str,[stdlib,*annotations])),
        '-d',str(tmp/'classes'),str(src)],check=True,timeout=60)
    subprocess.run([java,'-cp',os.pathsep.join(map(str,[tmp/'classes',stdlib])),
        'com.zui.zuicontrol.DropdownTestKt'],check=True,timeout=20)
print('EXACT_COMMIT_LISTENER_SHA256='+hashlib.sha256((commit+listener).encode()).hexdigest())
