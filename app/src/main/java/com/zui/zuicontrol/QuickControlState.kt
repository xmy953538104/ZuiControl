package com.zui.zuicontrol

/** Presentation intent only. A terminal receipt, never a state snapshot, ends a dispatched policy. */
internal class QuickControlState {
    enum class Phase { PENDING, CONFIRMED, FAILED, VERIFYING, UNKNOWN }
    data class Target(val user: Int, val pkg: String, val scope: String, val scene: Long)
    data class Operation(val sequence: Long, val control: String, val target: Target, val value: String, val trace: Long) {
        var dispatched = false; internal set
        var requestId = ""; internal set
        var phase = Phase.PENDING; internal set
        var detail = ""; internal set
    }
    data class Presentation(val value: String, val phase: Phase, val detail: String, val trace:Long=0)
    private data class Slot(var active: Operation, var next: Operation? = null)
    private val slots = mutableMapOf<String, Slot>()
    private var sequence = 0L

    @Synchronized fun offer(control: String, target: Target, value: String, confirmed: String, trace: Long): Operation? {
        val slot = slots[control]
        val current = slot?.active
        if (current != null && current.phase in setOf(Phase.PENDING, Phase.VERIFYING)) {
            if (current.target == target && current.value == value) { slot.next = null; return null }
            if (slot.next?.let { it.target == target && it.value == value } == true) return null
            if(!current.dispatched && current.phase==Phase.PENDING && current.target==target && value==confirmed){slots.remove(control);return null}
            val next = Operation(++sequence, control, target, value, trace)
            if (current.dispatched || current.phase == Phase.VERIFYING) { slot.next = next; return null }
            slot.active = next
            return next
        }
        if (value == confirmed) return null
        return Operation(++sequence, control, target, value, trace).also { slots[control] = Slot(it) }
    }
    @Synchronized fun dispatch(operation: Operation): Boolean {
        if (slots[operation.control]?.active !== operation || operation.dispatched) return false
        operation.dispatched = true
        return true
    }
    @Synchronized fun identify(operation: Operation, id: String) {
        check(slots[operation.control]?.active === operation)
        check(operation.requestId.isEmpty() || operation.requestId == id)
        operation.requestId = id
    }
    @Synchronized fun finish(operation: Operation, phase: Phase, detail: String = ""): Operation? {
        check(phase != Phase.PENDING)
        val slot = slots[operation.control]?.takeIf { it.active === operation } ?: return null
        operation.phase = phase; operation.detail = detail
        if (phase == Phase.VERIFYING) return null
        return slot.next?.also { slot.next = null; slot.active = it }
    }
    @Synchronized fun recover(control: String, target: Target, value: String, id: String): Operation {
        val operation = Operation(++sequence, control, target, value, 0).also {
            it.dispatched = true; it.requestId = id; it.phase = Phase.VERIFYING
        }
        val queued=slots[control]?.let { it.next ?: it.active.takeIf { active -> !active.dispatched && active.phase == Phase.PENDING } }
        slots[control] = Slot(operation,queued)
        return operation
    }
    @Synchronized fun presentation(control: String, target: Target): Presentation? {
        val slot = slots[control] ?: return null
        val operation = slot.next?.takeIf { it.target == target } ?: slot.active.takeIf { it.target == target } ?: return null
        val phase = if (operation === slot.next && slot.active.phase == Phase.VERIFYING) Phase.VERIFYING else operation.phase
        return Presentation(operation.value, phase, operation.detail,operation.trace)
    }
    @Synchronized fun verifying(): List<Operation> = slots.values.map { it.active }.filter { it.phase == Phase.VERIFYING }
}
