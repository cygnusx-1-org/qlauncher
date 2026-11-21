/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InternalIdleDetectorTest {
    private lateinit var time: FakeTime
    private lateinit var detector: InternalIdleDetector
    private val ticks = mutableListOf<Int>()
    private var cancels = 0
    private var actions = 0
    private var media = false

    @Before
    fun setUp() {
        time = FakeTime()
        detector = InternalIdleDetector(
            time, time,
            object : InternalIdleDetector.Listener {
                override fun onCountdown(secondsLeft: Int) {
                    ticks += secondsLeft
                }

                override fun onCountdownCancelled() {
                    cancels++
                }

                override fun onIdleAction() {
                    actions++
                }
            },
        )
        detector.shouldDefer = { media }
    }

    @Test
    fun countdownStartsAtTheTimeoutAndActsSixtySecondsLater() {
        detector.configure(true, TIMEOUT)
        time.advanceBy(TIMEOUT - 1)
        assertTrue(ticks.isEmpty())
        time.advanceBy(1)
        assertEquals(listOf(60), ticks)
        assertTrue(detector.isCountingDown)
        time.advanceBy(59_000)
        assertEquals((60 downTo 1).toList(), ticks)
        assertEquals(0, actions)
        time.advanceBy(1_000)
        assertEquals(1, actions)
        assertFalse(detector.isCountingDown)
    }

    @Test
    fun activityPostponesTheTimeout() {
        detector.configure(true, TIMEOUT)
        time.advanceBy(TIMEOUT - 1_000)
        assertFalse(detector.onActivity())
        time.advanceBy(TIMEOUT - 1)
        assertTrue(ticks.isEmpty())
        time.advanceBy(1)
        assertEquals(listOf(60), ticks)
    }

    @Test
    fun anyKeyCancelsTheCountdownAndRearms() {
        detector.configure(true, TIMEOUT)
        time.advanceBy(TIMEOUT + 10_000)
        assertTrue(detector.isCountingDown)
        assertTrue("the cancelling key is reported", detector.onActivity())
        assertEquals(1, cancels)
        assertFalse(detector.isCountingDown)
        time.advanceBy(120_000)
        assertEquals(0, actions)
        ticks.clear()
        time.advanceBy(TIMEOUT - 120_000)
        assertEquals("counts again after a full timeout", listOf(60), ticks)
    }

    @Test
    fun mediaPlaybackDefersUntilItStops() {
        media = true
        detector.configure(true, TIMEOUT)
        time.advanceBy(TIMEOUT + 5 * 60_000L)
        assertTrue(ticks.isEmpty())
        media = false
        time.advanceBy(60_000)
        assertEquals(listOf(60), ticks)
    }

    @Test
    fun afterTheActionItWaitsForActivity() {
        detector.configure(true, TIMEOUT)
        time.advanceBy(TIMEOUT + 60_000)
        assertEquals(1, actions)
        time.advanceBy(10 * TIMEOUT)
        assertEquals("not repeated without activity", 1, actions)
        detector.onActivity()
        time.advanceBy(TIMEOUT + 60_000)
        assertEquals(2, actions)
    }

    @Test
    fun screenOffPausesEverything() {
        detector.configure(true, TIMEOUT)
        time.advanceBy(TIMEOUT + 10_000)
        detector.pause()
        assertEquals(1, cancels)
        time.advanceBy(10 * TIMEOUT)
        assertEquals(0, actions)
        detector.resume()
        ticks.clear()
        time.advanceBy(TIMEOUT)
        assertEquals(listOf(60), ticks)
    }

    @Test
    fun turningItOffCancelsAndStops() {
        detector.configure(true, TIMEOUT)
        time.advanceBy(TIMEOUT + 10_000)
        detector.configure(false, TIMEOUT)
        assertEquals(1, cancels)
        time.advanceBy(10 * TIMEOUT)
        assertEquals(0, actions)
        assertFalse(detector.onActivity())
    }

    private companion object {
        const val TIMEOUT = 30 * 60_000L
    }
}
