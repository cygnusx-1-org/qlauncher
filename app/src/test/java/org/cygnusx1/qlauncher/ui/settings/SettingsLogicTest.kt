/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.Settings
import org.cygnusx1.qlauncher.ui.components.ColorPickerModel
import org.cygnusx1.qlauncher.ui.home.KeySequences
import org.cygnusx1.qlauncher.ui.settings.model.Row
import org.cygnusx1.qlauncher.ui.settings.model.ValueFormat
import org.cygnusx1.qlauncher.ui.settings.model.raw
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsLogicTest {
    private val defaults = Settings(emptyPreferences())

    @Test
    fun sliderModelOriginAndStops() {
        val size = Row.Slider(floatKey = Keys.Cards.size, title = raw(""), format = ValueFormat.Percent, centred = true)
        val m = SliderValues.model(size, defaults)
        assertEquals(11, m.stops)
        assertEquals(0.5f, m.originFraction, 1e-6f)
        assertEquals(0.5f, m.position, 1e-6f)
        assertEquals(1.1, m.stepped(1), 1e-6)
        assertEquals(1.5, m.stepped(99), 1e-6)
        assertEquals("100%", SliderValues.format(size, m.value))

        val hue = Row.Slider(intKey = Keys.Wallpaper.hue, title = raw(""))
        assertEquals(0.5f, SliderValues.model(hue, defaults).originFraction, 1e-6f)

        val spacing = Row.Slider(intKey = Keys.Cards.spacing, title = raw(""))
        val sm = SliderValues.model(spacing, defaults)
        assertEquals(0f, sm.originFraction)
        assertEquals(16, sm.stops)
    }

    @Test
    fun sliderFormats() {
        val delay = Row.Slider(intKey = Keys.Wallpaper.updateDelay, title = raw(""), format = ValueFormat.MinutesSeconds)
        assertEquals("1:00", SliderValues.format(delay, 60.0))
        assertEquals("0:15", SliderValues.format(delay, 15.0))
        assertEquals("60:00", SliderValues.format(delay, 3600.0))
        val focus = Row.Slider(choiceKey = Keys.Focus.scale, title = raw(""), choiceMin = 1)
        val m = SliderValues.model(focus, defaults)
        assertEquals(4, m.stops)
        assertEquals("3", SliderValues.format(focus, m.value))
        val idle = Row.Slider(floatKey = Keys.Idle.timeout, title = raw(""), format = ValueFormat.OneDecimal)
        assertEquals("2.5", SliderValues.format(idle, 2.5))
        assertEquals("2", SliderValues.format(idle, 2.0))
    }

    @Test
    fun sliderReadsStoredValue() {
        val s = Settings(mutablePreferencesOf(Keys.Cards.spacing.prefKey to 12))
        val spacing = Row.Slider(intKey = Keys.Cards.spacing, title = raw(""))
        assertEquals(12.0, SliderValues.model(spacing, s).value, 1e-9)
    }

    @Test
    fun timeEditing() {
        assertArrayEquals(intArrayOf(1, 0), TimeEditing.split(3_600_000L, timeOfDay = false, twelveHour = true))
        assertArrayEquals(intArrayOf(1, 30, 1), TimeEditing.split(13 * 3_600_000L + 30 * 60_000L, timeOfDay = true, twelveHour = true))
        assertEquals(13 * 3_600_000L + 30 * 60_000L, TimeEditing.join(intArrayOf(1, 30, 1), true))
        assertArrayEquals(intArrayOf(23, 0), TimeEditing.spin(intArrayOf(0, 0), 0, -1))
        assertArrayEquals(intArrayOf(0, 59), TimeEditing.spin(intArrayOf(0, 0), 1, -1))
        assertEquals(12 * 3_600_000L, TimeEditing.join(intArrayOf(0, 0, 1), true))
    }

    @Test
    fun colorPicker() {
        assertEquals(32, ColorPickerModel.swatches.size)
        assertEquals(0xFFFF0000.toInt(), ColorPickerModel.swatches[0])
        assertEquals(0xFFFFFFFF.toInt(), ColorPickerModel.mix(0xFFFF0000.toInt(), 100))
        assertEquals(0xFF000000.toInt(), ColorPickerModel.mix(0xFFFF0000.toInt(), -100))
        val c = ColorPickerModel.compose(0, 0, 255, dynamic = true)
        assertEquals(1, c and 1)
        assertEquals(0, ColorPickerModel.compose(0, 0, 255, dynamic = false) and 1)
        assertEquals(0, ColorPickerModel.compose(ColorPickerModel.TRANSPARENT_INDEX, 0, 255, true))
        // Dynamic white must not become "unset" (-1).
        val white = ColorPickerModel.compose(0, 100, 255, dynamic = true)
        assertEquals(1, white and 1)
        org.junit.Assert.assertNotEquals(-1, white)
        val (s, l, a) = ColorPickerModel.decompose(ColorPickerModel.compose(5, -40, 200, false))
        assertEquals(5, s)
        assertEquals(-40, l)
        assertEquals(200, a)
    }

    @Test
    fun keySequences() {
        val k = KeySequences()
        "URDLURD".forEach { assertNull(k.feed(it)) }
        assertEquals(KeySequences.SETTINGS, k.feed('L'))
        "UUDDLRL".forEach { assertNull(k.feed(it)) }
        assertEquals(KeySequences.EASTER_EGG, k.feed('R'))
    }
}
