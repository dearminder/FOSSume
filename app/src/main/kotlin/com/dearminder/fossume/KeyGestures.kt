package com.dearminder.fossume

/** Runs [block] after [delayMs]; the returned function cancels it. */
typealias Scheduler = (delayMs: Long, block: () -> Unit) -> (() -> Unit)

/**
 * Turns raw volume key presses into actions. Every key it takes over is swallowed, so presses
 * that turn out not to be a gesture are handed back as plain volume steps via [onVolume].
 *
 * - Holding a key for [AppSettings.longPressMs] fires its long action straight away.
 * - Holding both fires the "both" long action.
 * - Quick presses collect into a sequence; it fires as soon as it matches a combination nothing
 *   longer starts with, otherwise after [AppSettings.clickGapMs] without another press.
 *
 * Main thread only (key events and [scheduler] callbacks must arrive on the same thread).
 */
class KeyGestures(
    private val settings: () -> AppSettings,
    /** Asked when a gesture starts: is now a time to take the keys over at all? */
    private val shouldHandle: () -> Boolean,
    private val scheduler: Scheduler,
    private val onAction: (ActionBinding) -> Unit,
    private val onVolume: (VolumeKey) -> Unit
) {
    private val held = mutableSetOf<VolumeKey>()
    private val passed = mutableSetOf<VolumeKey>()
    private val pending = mutableListOf<VolumeKey>()
    /** The current hold already fired a long action - releasing it must not count as a click. */
    private var longFired = false
    private var cancelLong: (() -> Unit)? = null
    private var cancelGap: (() -> Unit)? = null

    /** Returns whether the event was consumed (true) or should reach the system (false). */
    fun onKeyDown(key: VolumeKey, repeatCount: Int): Boolean {
        // Auto-repeat while held: follow whatever was decided for the first event.
        if (repeatCount > 0 || key in held) return key in held
        val s = settings()
        if (!s.uses(key)) {
            finishSequence()
            passed += key
            return false
        }
        if (held.isEmpty() && pending.isEmpty() && !shouldHandle()) {
            passed += key
            return false
        }
        cancelGap?.invoke()
        cancelGap = null
        cancelLong?.invoke()
        cancelLong = null
        held += key
        val trigger = when {
            held.size == 2 -> LongTrigger.BOTH
            key == VolumeKey.UP -> LongTrigger.UP
            else -> LongTrigger.DOWN
        }
        if (held.size == 1) longFired = false
        val binding = s.longAction(trigger)
        if (binding.enabled && !longFired) {
            cancelLong = scheduler(s.longPressMs.toLong()) { fireLong(binding) }
        }
        return true
    }

    fun onKeyUp(key: VolumeKey): Boolean {
        if (passed.remove(key)) return false
        if (!held.remove(key)) return false
        cancelLong?.invoke()
        cancelLong = null
        if (longFired) {
            if (held.isEmpty()) longFired = false
            return true
        }
        pending += key
        evaluate()
        return true
    }

    /** Drops everything in progress, e.g. when the service is interrupted. */
    fun reset() {
        cancelLong?.invoke()
        cancelGap?.invoke()
        cancelLong = null
        cancelGap = null
        held.clear()
        passed.clear()
        pending.clear()
        longFired = false
    }

    private fun fireLong(binding: ActionBinding) {
        cancelLong = null
        longFired = true
        // Clicks right before the hold were a sequence of their own - settle it first.
        finishSequence()
        onAction(binding)
    }

    private fun evaluate() {
        val combos = settings().shortCombos.filter { it.binding.enabled }
        val longerPossible = combos.any { it.keys.size > pending.size && it.keys.subList(0, pending.size) == pending }
        if (!longerPossible) {
            finishSequence()
        } else {
            cancelGap = scheduler(settings().clickGapMs.toLong()) { cancelGap = null; finishSequence() }
        }
    }

    /** Fires the combination the clicks so far make up, or gives them back as volume steps. */
    private fun finishSequence() {
        cancelGap?.invoke()
        cancelGap = null
        if (pending.isEmpty()) return
        val keys = pending.toList()
        pending.clear()
        val match = settings().shortCombos.firstOrNull { it.binding.enabled && it.keys == keys }
        if (match != null) onAction(match.binding) else keys.forEach(onVolume)
    }
}
