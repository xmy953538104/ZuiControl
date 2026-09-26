package com.zui.zuicontrol

/** Pointer state only; uses the platform's double-tap timeout and view touch slop. */
internal class MonitorGesture(private val doubleTapMs: Long = 300) {
    enum class Phase { IDLE, PENDING, DRAGGING, CANCELLED }
    enum class Release { NONE, TO_CIRCLE, TO_BAR, START_RECORDING, STOP_RECORDING, DRAG_END, WAIT_TAP }
    var down = -1L; private set
    var phase = Phase.IDLE; private set
    var metric = 0; private set
    private var circleAtDown = false
    private var recordingAtDown = false
    private var pendingTap = -1L
    private var second = false
    private var cycleAt = 0L
    fun press(now: Long, circle: Boolean = true, recording: Boolean = false) {
        second = pendingTap >= 0 && now - pendingTap in 0..doubleTapMs && circle && !recording
        if (!second) pendingTap = -1
        down = now; phase = Phase.PENDING; circleAtDown = circle; recordingAtDown = recording
    }
    fun drag(): Boolean {
        pendingTap = -1; second = false
        if (!circleAtDown || phase !in listOf(Phase.PENDING, Phase.DRAGGING)) return false
        phase = Phase.DRAGGING; return true
    }
    fun cancel() { down = -1; pendingTap = -1; second = false; phase = Phase.CANCELLED }
    fun release(now: Long): Release {
        val action = when {
            phase == Phase.DRAGGING -> Release.DRAG_END
            phase != Phase.PENDING -> Release.NONE
            now - down !in 0..250 -> { pendingTap = -1; Release.NONE }
            recordingAtDown -> Release.STOP_RECORDING
            !circleAtDown -> Release.TO_CIRCLE
            second -> { pendingTap = -1; Release.START_RECORDING }
            else -> { pendingTap = now; Release.WAIT_TAP }
        }
        down = -1; phase = Phase.IDLE; second = false; cycleAt = now
        return action
    }
    fun confirm(now: Long): Release {
        if (down >= 0 || pendingTap < 0 || now - pendingTap < doubleTapMs) return Release.NONE
        pendingTap = -1
        return Release.TO_BAR
    }
    fun cycle(now: Long) {
        if (down < 0 && now - cycleAt >= 3000) { metric = (metric + 1) % 3; cycleAt = now }
    }
}
