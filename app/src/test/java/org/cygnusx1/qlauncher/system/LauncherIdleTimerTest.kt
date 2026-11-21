/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LauncherIdleTimerTest {
    private lateinit var time: FakeTime
    private lateinit var timer: LauncherIdleTimer

    @Before
    fun setUp() {
        time = FakeTime()
        timer = LauncherIdleTimer(time, time)
    }

    private val idle get() = timer.isIdle.value

    @Test
    fun existsOnlyWithTimeoutAboveZeroAndOpacityBelowFull() {
        assertFalse(LauncherIdleTimer.isEnabled(0L, 0f))
        assertFalse(LauncherIdleTimer.isEnabled(120_000L, 1f))
        assertTrue(LauncherIdleTimer.isEnabled(120_000L, 0.9f))
        assertTrue(LauncherIdleTimer.isEnabled(30_000L, 0f))
    }

    @Test
    fun neverIdleWhenOpacityIsFull() {
        timer.configure(2f, 1f)
        timer.start()
        time.advanceBy(60 * 60_000L)
        assertFalse(idle)
        assertTrue(time.pendingTasks == 0)
    }

    @Test
    fun neverIdleWhenTimeoutIsZero() {
        timer.configure(0f, 0f)
        timer.start()
        time.advanceBy(60 * 60_000L)
        assertFalse(idle)
    }

    @Test
    fun checksEveryHalfTimeoutAndEntersIdleAtTheFirstCheckPastTheTimeout() {
        timer.configure(2f, 0f) // 120 s, checks every 60 s
        timer.start()
        time.advanceBy(60_000)
        assertFalse(idle)
        time.advanceBy(59_999)
        assertFalse(idle)
        time.advanceBy(1)
        assertTrue("idle at the 120 s check", idle)
    }

    @Test
    fun idleStartsBetweenOneAndOneAndAHalfTimeoutsAfterTheLastInput() {
        timer.configure(2f, 0f)
        timer.start()
        time.advanceBy(10_000)
        timer.onKeyEvent(isDown = true)
        timer.onKeyEvent(isDown = false) // last input at 10 s
        time.advanceBy(110_000) // the 120 s check: only 110 s idle
        assertFalse(idle)
        time.advanceBy(59_999)
        assertFalse(idle)
        time.advanceBy(1) // the 180 s check: 170 s idle, within 1x..1.5x of 120 s
        assertTrue(idle)
    }

    @Test
    fun whileIdleKeysAreSwallowedAndTheFirstUpWakes() {
        timer.configure(1f, 0f)
        timer.start()
        time.advanceBy(60_000)
        assertTrue(idle)
        assertTrue("down swallowed", timer.onKeyEvent(isDown = true))
        assertTrue("still idle after the down", idle)
        assertTrue("up swallowed", timer.onKeyEvent(isDown = false))
        assertFalse("the up woke the UI", idle)
        assertFalse("later keys pass", timer.onKeyEvent(isDown = true))
    }

    @Test
    fun otherInputWakesAtOnce() {
        timer.configure(1f, 0f)
        timer.start()
        time.advanceBy(60_000)
        assertTrue(timer.onUserInput())
        assertFalse(idle)
        assertFalse(timer.onUserInput())
    }

    @Test
    fun wakingRestartsTheCount() {
        timer.configure(1f, 0f) // 60 s, checks every 30 s
        timer.start()
        time.advanceBy(60_000)
        assertTrue(idle)
        timer.onUserInput()
        time.advanceBy(30_000)
        assertFalse(idle)
        time.advanceBy(30_000)
        assertTrue(idle)
    }

    @Test
    fun stopWakesAndStartIgnoresEarlierInput() {
        timer.configure(1f, 0f)
        timer.start()
        time.advanceBy(60_000)
        assertTrue(idle)
        timer.stop()
        assertFalse(idle)
        time.advanceBy(10 * 60_000L)
        assertFalse("no checks while stopped", idle)
        timer.start()
        time.advanceBy(30_000)
        assertFalse("the time away does not count", idle)
        time.advanceBy(30_000)
        assertTrue(idle)
    }

    @Test
    fun turningIdleModeOffWakes() {
        timer.configure(1f, 0f)
        timer.start()
        time.advanceBy(60_000)
        assertTrue(idle)
        timer.configure(1f, 1f)
        assertFalse(idle)
        time.advanceBy(10 * 60_000L)
        assertFalse(idle)
    }
}
