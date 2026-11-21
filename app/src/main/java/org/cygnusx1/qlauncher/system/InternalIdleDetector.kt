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
 * *Power › Enable internal idle detection*: user activity seen by the accessibility service
 * resets a timer of *Idle timeout*; when it runs out a 60-second countdown is shown ("User
 * inactivity detected, shutdown in 60s / Press any button to cancel") and, unless some activity
 * cancels it, the idle action runs. After the action the detector waits for the next activity
 * before it arms again, so a screensaver is not restarted over and over.
 *
 * [shouldDefer] is asked when the timeout runs out; while it returns true (media is playing and
 * *Ignore idle mode during media playback* applies) the check is repeated every
 * [deferRecheckMillis] instead of starting the countdown.
 *
 * Pure: the clock and the scheduler are injected; call it from the scheduler's thread.
 */
class InternalIdleDetector(
    private val clock: MonotonicClock,
    private val scheduler: TaskScheduler,
    private val listener: Listener,
    val countdownSeconds: Int = COUNTDOWN_SECONDS,
    val deferRecheckMillis: Long = 60_000L,
) {
    interface Listener {
        /** The countdown started or ticked; [secondsLeft] goes from [countdownSeconds] to 1. */
        fun onCountdown(secondsLeft: Int)

        /** Activity cancelled the countdown, or detection was turned off during it. */
        fun onCountdownCancelled()

        /** The countdown reached zero: run the idle action. */
        fun onIdleAction()
    }

    var shouldDefer: () -> Boolean = { false }

    private var enabled = false
    private var timeoutMillis = 0L
    private var paused = false
    private var armed = false
    private var lastActivity = clock.nowMillis()
    private var timer: Cancellable? = null
    private var secondsLeft = 0

    /** True while the countdown is showing. */
    val isCountingDown: Boolean get() = secondsLeft > 0

    fun configure(enabled: Boolean, timeoutMillis: Long) {
        val active = enabled && timeoutMillis > 0L
        if (active == this.enabled && timeoutMillis == this.timeoutMillis) return
        this.enabled = active
        this.timeoutMillis = timeoutMillis
        stopCountdown(notify = true)
        cancelTimer()
        if (active) {
            lastActivity = clock.nowMillis()
            armed = true
            schedule(timeoutMillis)
        } else {
            armed = false
        }
    }

    /**
     * The user did something. Returns true when this activity cancelled a running countdown, so
     * the key that did it can be swallowed.
     */
    fun onActivity(): Boolean {
        lastActivity = clock.nowMillis()
        if (!enabled || paused) return false
        val cancelled = isCountingDown
        if (cancelled) stopCountdown(notify = true)
        if (!armed || cancelled) {
            armed = true
            cancelTimer()
            schedule(timeoutMillis)
        }
        return cancelled
    }

    /** The screen turned off: nothing counts while it is off. */
    fun pause() {
        paused = true
        stopCountdown(notify = true)
        cancelTimer()
    }

    /** The screen turned on again; counts as activity. */
    fun resume() {
        paused = false
        armed = false
        onActivity()
    }

    fun stop() {
        enabled = false
        armed = false
        stopCountdown(notify = false)
        cancelTimer()
    }

    private fun schedule(delay: Long) {
        timer = scheduler.schedule(delay.coerceAtLeast(0L)) {
            timer = null
            onTimer()
        }
    }

    private fun onTimer() {
        if (!enabled || paused || !armed) return
        if (isCountingDown) {
            secondsLeft--
            if (secondsLeft > 0) {
                listener.onCountdown(secondsLeft)
                schedule(1_000L)
            } else {
                armed = false
                listener.onIdleAction()
            }
            return
        }
        val idleFor = clock.nowMillis() - lastActivity
        if (idleFor < timeoutMillis) {
            schedule(timeoutMillis - idleFor)
            return
        }
        if (shouldDefer()) {
            schedule(deferRecheckMillis)
            return
        }
        secondsLeft = countdownSeconds
        listener.onCountdown(secondsLeft)
        schedule(1_000L)
    }

    private fun stopCountdown(notify: Boolean) {
        if (!isCountingDown) return
        secondsLeft = 0
        if (notify) listener.onCountdownCancelled()
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
    }

    companion object {
        const val COUNTDOWN_SECONDS = 60
    }
}
