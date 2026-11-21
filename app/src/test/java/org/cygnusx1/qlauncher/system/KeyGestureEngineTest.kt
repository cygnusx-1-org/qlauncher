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

class KeyGestureEngineTest {
    private lateinit var time: FakeTime
    private lateinit var engine: KeyGestureEngine
    private val log = mutableListOf<String>()

    private fun binding(name: String, single: Boolean = true, double: Boolean = false, long: Boolean = false) =
        KeyGestureEngine.Binding(
            onSingle = if (single) ({ log += "$name:single@${time.now - T0}" }) else null,
            onDouble = if (double) ({ log += "$name:double@${time.now - T0}" }) else null,
            onLong = if (long) ({ log += "$name:long@${time.now - T0}" }) else null,
        )

    @Before
    fun setUp() {
        time = FakeTime(T0)
        engine = KeyGestureEngine(time, time)
    }

    private fun down(repeat: Int = 0) = engine.onKey(KEY, isDown = true, repeatCount = repeat)
    private fun up() = engine.onKey(KEY, isDown = false)

    @Test
    fun unboundKeysPassThrough() {
        engine.setBindings(mapOf(KEY to binding("k")))
        assertFalse(engine.onKey(OTHER, true))
        assertFalse(engine.onKey(OTHER, false))
        assertTrue(log.isEmpty())
    }

    @Test
    fun singleWithoutDoubleBindingFiresOnUp() {
        engine.setBindings(mapOf(KEY to binding("k")))
        assertTrue(down())
        time.advanceBy(100)
        assertTrue(up())
        assertEquals(listOf("k:single@100"), log)
    }

    @Test
    fun keyHeldPast300msIsLongPress() {
        engine.setBindings(mapOf(KEY to binding("k", long = true)))
        down()
        time.advanceBy(299)
        assertTrue(log.isEmpty())
        time.advanceBy(1)
        assertEquals(listOf("k:long@300"), log)
        time.advanceBy(200)
        assertTrue(up())
        assertEquals("no single after a long press", listOf("k:long@300"), log)
    }

    @Test
    fun releaseBefore300msIsSingleNotLong() {
        engine.setBindings(mapOf(KEY to binding("k", long = true)))
        down()
        time.advanceBy(299)
        up()
        time.advanceBy(1_000)
        assertEquals(listOf("k:single@299"), log)
    }

    @Test
    fun repeatsProveALongPressWhenTheSchedulerIsLate() {
        engine.setBindings(mapOf(KEY to binding("k", long = true)))
        down()
        time.skipWithoutRunning(350)
        assertTrue(down(repeat = 1))
        assertEquals(listOf("k:long@350"), log)
        time.advanceBy(10)
        up()
        assertEquals(listOf("k:long@350"), log)
    }

    @Test
    fun secondPressWithin400msIsDoubleAndNoSingle() {
        engine.setBindings(mapOf(KEY to binding("k", double = true)))
        down(); time.advanceBy(80); up()
        time.advanceBy(300)
        assertTrue(down())
        assertEquals(listOf("k:double@380"), log)
        time.advanceBy(80)
        assertTrue(up())
        time.advanceBy(2_000)
        assertEquals(listOf("k:double@380"), log)
    }

    @Test
    fun singleIsDelayedUntil400msAfterTheFirstDownWhenDoubleIsBound() {
        engine.setBindings(mapOf(KEY to binding("k", double = true)))
        down(); time.advanceBy(100); up()
        time.advanceBy(299)
        assertTrue("not before 400 ms", log.isEmpty())
        time.advanceBy(1)
        assertEquals(listOf("k:single@400"), log)
    }

    @Test
    fun secondPressAfter400msIsTwoSingles() {
        engine.setBindings(mapOf(KEY to binding("k", double = true)))
        down(); time.advanceBy(100); up()
        time.advanceBy(350)
        assertEquals(listOf("k:single@400"), log)
        down(); time.advanceBy(100); up()
        time.advanceBy(400)
        assertEquals(listOf("k:single@400", "k:single@850"), log)
    }

    @Test
    fun lateSchedulerStillSeparatesTwoSlowPresses() {
        engine.setBindings(mapOf(KEY to binding("k", double = true)))
        down(); time.advanceBy(100); up()
        time.skipWithoutRunning(400) // now 500 ms after the first down, pending single not run yet
        down()
        assertEquals("the first press is settled as single", listOf("k:single@500"), log)
        time.advanceBy(50); up()
        time.advanceBy(400)
        assertEquals(listOf("k:single@500", "k:single@900"), log)
    }

    @Test
    fun releaseExactlyAtTheWindowFiresAtOnce() {
        engine.setBindings(mapOf(KEY to binding("k", double = true)))
        down(); time.advanceBy(450); up()
        time.advanceBy(0)
        assertEquals(listOf("k:single@450"), log)
    }

    @Test
    fun ownedKeyConsumesRepeatsAndUp() {
        engine.setBindings(mapOf(KEY to binding("k")))
        assertTrue(down())
        assertTrue(down(repeat = 1))
        assertTrue(up())
    }

    @Test
    fun upWithoutTrackedDownPassesThrough() {
        engine.setBindings(mapOf(KEY to binding("k")))
        assertFalse(up())
        assertFalse(down(repeat = 3))
        assertTrue(log.isEmpty())
    }

    @Test
    fun passThroughKeyLetsTheFirstPressGoAndTakesTheSecond() {
        engine.setBindings(mapOf(KEY to binding("m", single = false, double = true)))
        assertFalse("first press reaches the system", down())
        time.advanceBy(60)
        assertFalse(up())
        time.advanceBy(200)
        assertTrue("second press is ours", down())
        assertEquals(listOf("m:double@260"), log)
        assertTrue(down(repeat = 1))
        time.advanceBy(60)
        assertTrue(up())
        time.advanceBy(100)
        assertFalse("a third press starts over", down())
        assertFalse(up())
        assertEquals(listOf("m:double@260"), log)
    }

    @Test
    fun passThroughKeyPressedSlowlyNeverFires() {
        engine.setBindings(mapOf(KEY to binding("m", single = false, double = true)))
        assertFalse(down()); assertFalse(up())
        time.advanceBy(401)
        assertFalse(down()); assertFalse(up())
        assertTrue(log.isEmpty())
    }

    @Test
    fun replacingBindingsDropsAPendingSingle() {
        engine.setBindings(mapOf(KEY to binding("k", double = true)))
        down(); time.advanceBy(50); up()
        engine.setBindings(emptyMap())
        time.advanceBy(1_000)
        assertTrue(log.isEmpty())
        assertEquals(0, time.pendingTasks)
    }

    @Test
    fun doubleThenLongBindingsTogether() {
        engine.setBindings(mapOf(KEY to binding("k", double = true, long = true)))
        down()
        time.advanceBy(500)
        up()
        time.advanceBy(1_000)
        assertEquals(listOf("k:long@300"), log)
    }

    private companion object {
        const val T0 = 1_000_000L
        const val KEY = 3 // KEYCODE_HOME
        const val OTHER = 19 // KEYCODE_DPAD_UP
    }
}
