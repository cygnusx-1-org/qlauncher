/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.system

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The home screen's launcher idle mode (*Appearance › Launcher Idle Mode*).
 *
 * - Idle mode exists only when the timeout is above 0 **and** the idle opacity is below 100%.
 * - While [start]ed, a check runs every half timeout and enters idle when the last key or touch
 *   is older than the timeout, so idle begins between 1× and 1.5× the timeout after the last input.
 * - While idle every key is swallowed and the first key **up** only wakes the UI.
 *
 * Pure: the clock and the scheduler are injected; call it from the scheduler's thread.
 */
class LauncherIdleTimer(
    private val clock: MonotonicClock,
    private val scheduler: TaskScheduler,
) {
    private val _isIdle = MutableStateFlow(false)

    /** True while the UI should be faded to the idle opacity. */
    val isIdle: StateFlow<Boolean> = _isIdle.asStateFlow()

    private var timeoutMillis = 0L
    private var opacity = 1f
    private var started = false
    private var lastInput = clock.nowMillis()
    private var check: Cancellable? = null

    /** Whether idle mode exists with the current configuration. */
    val enabled: Boolean get() = isEnabled(timeoutMillis, opacity)

    /**
     * Applies *Timeout before idle* (minutes, `Keys.Idle.timeout`) and *UI Opacity* (0..1,
     * `Keys.Idle.opacity`). Turning idle mode off wakes the UI.
     */
    fun configure(timeoutMinutes: Float, opacity: Float) {
        configureMillis((timeoutMinutes.toDouble() * 60_000.0).toLong(), opacity)
    }

    fun configureMillis(timeoutMillis: Long, opacity: Float) {
        if (timeoutMillis == this.timeoutMillis && opacity == this.opacity) return
        this.timeoutMillis = timeoutMillis.coerceAtLeast(0L)
        this.opacity = opacity
        if (!enabled) _isIdle.value = false
        restartLoop()
    }

    /** The home screen is visible (call from `onResume`). Input before this point does not count. */
    fun start() {
        started = true
        lastInput = clock.nowMillis()
        _isIdle.value = false
        restartLoop()
    }

    /** The home screen is no longer visible (call from `onPause`). */
    fun stop() {
        started = false
        check?.cancel()
        check = null
        _isIdle.value = false
    }

    /**
     * A key event reached the home screen. Returns true when it must be swallowed: while idle
     * both the down and the up are swallowed, and the up wakes the UI.
     */
    fun onKeyEvent(isDown: Boolean): Boolean {
        lastInput = clock.nowMillis()
        if (!_isIdle.value) return false
        if (!isDown) _isIdle.value = false
        return true
    }

    /**
     * Any other input (a touch, a generic motion). Wakes the UI at once. Returns true when the UI
     * was idle, so the caller can swallow the event.
     */
    fun onUserInput(): Boolean {
        lastInput = clock.nowMillis()
        val wasIdle = _isIdle.value
        if (wasIdle) _isIdle.value = false
        return wasIdle
    }

    private fun restartLoop() {
        check?.cancel()
        check = null
        if (started && enabled) scheduleCheck()
    }

    private fun scheduleCheck() {
        val period = (timeoutMillis / 2).coerceAtLeast(1L)
        check = scheduler.schedule(period) {
            check = null
            if (!started || !enabled) return@schedule
            if (!_isIdle.value && clock.nowMillis() - lastInput >= timeoutMillis) _isIdle.value = true
            scheduleCheck()
        }
    }

    companion object {
        /** 100% (allowing for float steps of 0.1) means the UI never fades, so idle mode is off. */
        fun isEnabled(timeoutMillis: Long, opacity: Float): Boolean = timeoutMillis > 0L && opacity < 0.995f
    }
}
