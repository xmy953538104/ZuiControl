package com.zui.zuicontrol

/** Pure gesture accounting; never opens storage or starts sampling itself. */
internal class MonitorGesture {
    var down = -1L; private set
    var cancelled = false; private set
    var completed = false; private set
    var metric = 0; private set
    private var cycleAt = 0L
    fun press(now: Long) { down=now;cancelled=false;completed=false }
    fun cancel() { down=-1;cancelled=true }
    fun complete(now: Long): Boolean {
        if(cancelled || completed || down<0 || now-down<2000)return false
        completed=true;return true
    }
    fun shortRelease(now: Long) = down>=0 && !cancelled && !completed && now-down<=250
    fun release(now: Long) { down=-1;cycleAt=now }
    fun cycle(now: Long) {
        if(down<0 && now-cycleAt>=3000){metric=(metric+1)%3;cycleAt=now}
    }
}
