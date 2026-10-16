"""Execute actual submit/section cancellation and bind guards; pixel/recycling counts are device gates."""
from pathlib import Path
import sys
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'tests/command_plane'))
import sys
sys.path.insert(0,str(ROOT/'tests'))
from FrontendSource import read_frontend
from TestUtilityTruth import compile_run
source=(ROOT/'app/src/main/java/com/zui/zuicontrol/OwnerMasterList.kt').read_text(encoding='utf8')
main=read_frontend(ROOT/'app/src/main/java/com/zui/zuicontrol/MainActivity.kt')
assert 'recycled as? RowView ?: RowView()' in source and 'setOnClickListener{choose(key)}' in source
assert 'key=value.key' in source and 'bound!=value||palette!=ui.dark' in source
assert 'masterViewport ?: OwnerMasterList' in main and 'recycled.showSection(session.section)' in main
body=main.split('private fun populateList(',1)[1].split('private fun select(pkg:',1)[0]
assert 'removeAllViews' not in body and 'list.submit(rows.filter' in body
assert 'query.isBlank()||it.key.contains(query,true)||it.title.contains(query,true)' in body
assert 'save' not in body.lower() and 'commit' not in body.lower()
methods=source[source.index('    fun showSection('):source.index('    fun select(')]
row=source.split('    data class Row(',1)[1].split('\n',1)[0]
program="""package com.zui.zuicontrol
class FakeChild(val top:Int)
class FakeList{
 var firstVisiblePosition=0;var target:Pair<Int,Int>?=null;val posted=mutableListOf<Runnable>()
 fun getChildAt(i:Int)=FakeChild(7)
 fun removeCallbacks(r:Runnable){posted.remove(r)}
 fun post(r:Runnable):Boolean{posted.add(r);return true}
 fun setSelectionFromTop(p:Int,y:Int){target=p to y}
 fun setSelection(p:Int){firstVisiblePosition=p}
}
class FakeText{var text=""}
class Rows{
 data class Chip(val text:String,val tier:Int=0,val created:Boolean=false)
 data class Row(ROW
 var rows=emptyList<Row>();var selected="";var section="";var ticket=0L;var restoreScroll:Runnable?=null
 val list=FakeList();val empty=FakeText();var notices=0
 private val adapter=object:Any(){fun notifyDataSetChanged(){notices++}}
 METHODS
}
fun main(){
 val v=Rows();val a=Rows.Row("a","A");val b=Rows.Row("b","B");val c=Rows.Row("c","C")
 v.showSection("tune");v.submit(listOf(a,b,c),"empty");v.list.firstVisiblePosition=1
 v.submit(listOf(c,b,a),"empty");val old=checkNotNull(v.restoreScroll);val notices=v.notices
 v.submit(listOf(c,b,a),"changed");check(v.notices==notices&&v.empty.text=="changed")
 v.submit(listOf(a,b),"empty");val fresh=checkNotNull(v.restoreScroll);old.run();check(v.restoreScroll===fresh)
 fresh.run();check(v.list.target==1 to 7);check(v.restoreScroll==null)
 v.submit(listOf(c),"empty");checkNotNull(v.restoreScroll).run();check(v.list.target==0 to 0)
 v.showSection("thread");check(v.rows.isEmpty()&&v.selected.isEmpty()&&v.list.firstVisiblePosition==0)
 check(v.restoreScroll==null);println("OWNER_LIST_ANCHOR_STALE_REQUEST_SECTION_ISOLATION_PASS")
}
""".replace('ROW',row).replace('METHODS',methods)
compile_run({'OwnerRows.kt':program},'com.zui.zuicontrol.OwnerRowsKt',[])
