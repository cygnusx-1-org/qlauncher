/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.system

/**
 * Turns raw key downs and ups into single, double and long presses, with Projectivy's timings:
 *
 * - a key still held [longPressMillis] (300 ms) after its down is a long press;
 * - a second down within [doublePressMillis] (400 ms) of the first is a double press;
 * - when a key has a double-press binding, its single press waits until those 400 ms have passed
 *   (counted from the first down), so a double press never fires the single action first.
 *
 * A [Binding] whose [Binding.onSingle] is null leaves the single press to the system: the first
 * press passes through untouched (an accessibility service cannot re-inject a key it swallowed),
 * and only a second press within the window is consumed and fires [Binding.onDouble]. This is
 * how Menu keeps working in apps while Menu twice opens the help overlay.
 *
 * Pure: the clock and the scheduler are injected, and every call must come from the scheduler's
 * thread (the main thread in the app).
 */
class KeyGestureEngine(
    private val clock: MonotonicClock,
    private val scheduler: TaskScheduler,
    val longPressMillis: Long = LONG_PRESS_MILLIS,
    val doublePressMillis: Long = DOUBLE_PRESS_MILLIS,
) {
    /** What a key does. Null actions are not bound. */
    class Binding(
        val onSingle: (() -> Unit)?,
        val onDouble: (() -> Unit)? = null,
        val onLong: (() -> Unit)? = null,
    ) {
        /** True when the engine owns every press of the key (and therefore consumes them all). */
        val ownsKey: Boolean get() = onSingle != null
    }

    private class KeyState {
        // Owned keys.
        var pressDownAt = NONE
        var pressIsSecond = false
        var longFired = false
        var longTask: Cancellable? = null
        var firstDownAt = NONE
        var pendingSingle: Cancellable? = null

        // Pass-through keys.
        var passLastDownAt = NONE
        var passConsuming = false

        val pressing: Boolean get() = pressDownAt != NONE
    }

    private var bindings: Map<Int, Binding> = emptyMap()
    private val states = HashMap<Int, KeyState>()

    /** Replaces every binding. Pending single presses and held keys are forgotten. */
    fun setBindings(newBindings: Map<Int, Binding>) {
        reset()
        bindings = newBindings.toMap()
    }

    fun binding(keyCode: Int): Binding? = bindings[keyCode]

    /** Cancels pending actions and forgets keys being held (for example when the service stops). */
    fun reset() {
        for (st in states.values) {
            st.longTask?.cancel()
            st.pendingSingle?.cancel()
        }
        states.clear()
    }

    /**
     * Feeds one key event. Returns true when the event must be consumed (not passed on to the
     * system or the focused app).
     */
    fun onKey(keyCode: Int, isDown: Boolean, repeatCount: Int = 0): Boolean {
        val binding = bindings[keyCode] ?: return false
        val st = states.getOrPut(keyCode) { KeyState() }
        return if (binding.ownsKey) ownedKey(binding, st, isDown, repeatCount) else passThroughKey(binding, st, isDown, repeatCount)
    }

    private fun ownedKey(binding: Binding, st: KeyState, isDown: Boolean, repeatCount: Int): Boolean {
        val now = clock.nowMillis()
        if (isDown) {
            if (repeatCount > 0) {
                if (!st.pressing) return false // The down went to the system before we tracked it.
                // A late scheduler must not lose a long press the repeats already prove.
                if (!st.longFired && !st.pressIsSecond && binding.onLong != null && now - st.pressDownAt >= longPressMillis) {
                    fireLong(binding, st)
                }
                return true
            }
            st.longTask?.cancel()
            st.longTask = null
            val pending = st.pendingSingle
            if (pending != null) {
                pending.cancel()
                st.pendingSingle = null
                if (now - st.firstDownAt <= doublePressMillis) {
                    st.pressDownAt = now
                    st.pressIsSecond = true
                    st.longFired = false
                    binding.onDouble?.invoke()
                    return true
                }
                // The window passed but the scheduler has not run yet: the first press was single.
                binding.onSingle?.invoke()
            }
            st.firstDownAt = now
            st.pressDownAt = now
            st.pressIsSecond = false
            st.longFired = false
            if (binding.onLong != null) {
                val downAt = now
                st.longTask = scheduler.schedule(longPressMillis) {
                    st.longTask = null
                    if (st.pressDownAt == downAt && !st.longFired) fireLong(binding, st)
                }
            }
            return true
        }

        // Up.
        if (!st.pressing) return false
        val wasSecond = st.pressIsSecond
        val wasLong = st.longFired
        st.pressDownAt = NONE
        st.pressIsSecond = false
        st.longFired = false
        st.longTask?.cancel()
        st.longTask = null
        if (wasSecond || wasLong) return true
        if (binding.onDouble != null) {
            val delay = (st.firstDownAt + doublePressMillis - now).coerceAtLeast(0L)
            st.pendingSingle = scheduler.schedule(delay) {
                st.pendingSingle = null
                binding.onSingle?.invoke()
            }
        } else {
            binding.onSingle?.invoke()
        }
        return true
    }

    private fun fireLong(binding: Binding, st: KeyState) {
        st.longFired = true
        st.longTask?.cancel()
        st.longTask = null
        binding.onLong?.invoke()
    }

    private fun passThroughKey(binding: Binding, st: KeyState, isDown: Boolean, repeatCount: Int): Boolean {
        val now = clock.nowMillis()
        if (isDown) {
            if (repeatCount > 0) return st.passConsuming
            val last = st.passLastDownAt
            if (binding.onDouble != null && last != NONE && now - last <= doublePressMillis) {
                st.passLastDownAt = NONE
                st.passConsuming = true
                binding.onDouble.invoke()
                return true
            }
            st.passLastDownAt = now
            st.passConsuming = false
            return false
        }
        if (st.passConsuming) {
            st.passConsuming = false
            return true
        }
        return false
    }

    companion object {
        const val LONG_PRESS_MILLIS = 300L
        const val DOUBLE_PRESS_MILLIS = 400L
        private const val NONE = Long.MIN_VALUE
    }
}
