package com.zui.zuicontrol

/** One pointer gesture; storage, windows and recording commands remain with the controller. */
internal class MonitorGesture {
    enum class Phase { IDLE, PENDING, DRAGGING, COMPLETED, CANCELLED }
    enum class Release { NONE, TO_CIRCLE, TO_BAR, STOP_RECORDING, DRAG_END }
    var down = -1L; private set
    var phase = Phase.IDLE; private set
    var completed = false; private set
    var metric = 0; private set
    private var circleAtDown = false
    private var recordingAtDown = false
    private var cycleAt = 0L
    fun press(now: Long, circle: Boolean = true, recording: Boolean = false) {
        down = now; phase = Phase.PENDING; completed = false
        circleAtDown = circle; recordingAtDown = recording
    }
    fun drag(): Boolean {
        if (!circleAtDown || phase !in listOf(Phase.PENDING, Phase.COMPLETED, Phase.DRAGGING)) return false
        phase = Phase.DRAGGING
        return true
    }
    fun cancel() { down = -1; phase = Phase.CANCELLED }
    fun arming(now: Long) = circleAtDown && !recordingAtDown && phase == Phase.PENDING && now - down > 250
    fun complete(now: Long): Boolean {
        if (!circleAtDown || recordingAtDown || phase != Phase.PENDING || now - down < 2000) return false
        completed = true; phase = Phase.COMPLETED
        return true
    }
    /** Reset before the caller changes shape/state, so this DOWN cannot arm the resulting circle. */
    fun release(now: Long): Release {
        val action = when {
            phase == Phase.DRAGGING -> Release.DRAG_END
            phase != Phase.PENDING -> Release.NONE
            !circleAtDown -> Release.TO_CIRCLE
            now - down > 250 -> Release.NONE
            recordingAtDown -> Release.STOP_RECORDING
            else -> Release.TO_BAR
        }
        down = -1; phase = Phase.IDLE; cycleAt = now
        return action
    }
    fun cycle(now: Long) {
        if (down < 0 && now - cycleAt >= 3000) { metric = (metric + 1) % 3; cycleAt = now }
    }
}
