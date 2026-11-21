/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.home

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The home layout against the `uiautomator dump` bounds (shield2, default
 * settings, first Favorites card focused).
 */
class HomeGeometryTest {
    private val defaults = HomeGeometry.Params.from(Settings(emptyPreferences()))

    private fun r(v: Float) = v.roundToInt()

    @Test
    fun cardSizes() {
        assertEquals(144f, HomeGeometry.cardHeight(0.8f, 1.0f))
        assertEquals(256f, HomeGeometry.cardWidth(144f, 16f / 9f))
        assertEquals(100f, HomeGeometry.cardHeight(0.8f, 0.7f))
        assertEquals(176f, HomeGeometry.cardWidth(100f, 16f / 9f))
        assertEquals(216f, HomeGeometry.cardHeight(0.8f, 1.5f))
        assertEquals(384f, HomeGeometry.cardWidth(216f, 16f / 9f))
        assertEquals(180f, HomeGeometry.cardHeight(1.0f, 1.0f))
        assertEquals(320f, HomeGeometry.cardWidth(180f, 16f / 9f))
    }

    @Test
    fun defaultRowMatchesDump() {
        val b = HomeGeometry.block(defaults, 144f)
        val starts = HomeGeometry.blockStarts(defaults, listOf(b, b), 0, null)
        // Row grid [96,540][1824,716].
        assertEquals(540, r(starts[0] + b.gridTop))
        assertEquals(716, r(starts[0] + b.gridBottom))
        assertEquals(96, r(defaults.rowLeft))
        assertEquals(1824, r(defaults.rowRight))
        // Category title [176,463][311,511].
        assertEquals(463, r(starts[0] + b.titleTop!!))
        // Focused card title top 745.
        assertEquals(745, r(starts[0] + b.cardTitleTop))
        // Next category title at 812, next grid at 889 (+-1).
        assertEquals(812, r(starts[1] + b.titleTop))
        assertEquals(889f, starts[1] + b.gridTop, 0.01f)
        assertEquals(905f, starts[1] + b.cardTop(0, defaults.spacing), 0.01f)
        // Cards at x = 112 and 384, 556..700.
        val xs = HomeGeometry.cardXs(defaults, listOf(256f, 256f))
        assertEquals(112, r(xs[0]))
        assertEquals(384, r(xs[1]))
        assertEquals(556, r(starts[0] + b.cardTop(0, defaults.spacing)))
        // Focused card scaled 1.3 about its centre: right edge 406.
        val right = xs[0] + 128f + 128f * 1.3f
        assertEquals(406, r(right))
    }

    @Test
    fun pitchVariants() {
        val b = HomeGeometry.block(defaults, 144f)
        assertEquals(349f, b.pitch, 0.01f)
        val noCardTitle = HomeGeometry.block(defaults.copy(showCardTitle = false), 144f)
        assertEquals(302f, noCardTitle.pitch, 1.5f)
        val noCategoryTitle = HomeGeometry.block(defaults.copy(showCategoryTitle = false), 144f)
        assertEquals(281f, noCategoryTitle.pitch, 1.5f)
    }

    @Test
    fun focusSizeAndGlowMoveNextRow() {
        val base = HomeGeometry.block(defaults, 144f).pitch
        val xs = HomeGeometry.block(defaults.copy(zoom = 1.0f), 144f).pitch
        val l = HomeGeometry.block(defaults.copy(zoom = 1.45f), 144f).pitch
        assertEquals(-42f, xs - base, 2.5f)
        assertEquals(22f, l - base, 0.01f)
        val s = Settings(mutablePreferencesOf(Keys.Focus.glowSize.prefKey to 5))
        val glow5 = HomeGeometry.block(HomeGeometry.Params.from(s), 144f).pitch
        assertEquals(-8f, glow5 - base, 0.5f)
    }

    @Test
    fun topMarginIsLimited() {
        val p = defaults.copy(topMargin = 0.85f)
        val b = HomeGeometry.block(p, 144f)
        assertEquals(869f, HomeGeometry.focusedGridTop(p, b), 0.5f)
    }

    @Test
    fun spacingChangesGaps() {
        val p = defaults.copy(spacing = 16f)
        val xs = HomeGeometry.cardXs(p, listOf(256f, 256f))
        assertEquals(112f, xs[0])
        assertEquals(112f + 256f + 32f, xs[1])
        val p1 = defaults.copy(spacing = 1f)
        assertEquals(112f + 256f + 2f, HomeGeometry.cardXs(p1, listOf(256f, 256f))[1])
    }

    @Test
    fun slidingRowKeepsFocusInFirstSlot() {
        val widths = List(6) { 256f }
        val xs = HomeGeometry.cardXs(defaults, widths)
        assertEquals(xs[3] - xs[0], HomeGeometry.rowScroll(defaults, xs, widths, 3, moving = false, previous = 0f))
        // Moving selection stays put while the card is inside the row.
        assertEquals(0f, HomeGeometry.rowScroll(defaults, xs, widths, 3, moving = true, previous = 0f))
    }
}
