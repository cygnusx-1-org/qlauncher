/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.home

import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.Settings
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * The home-screen layout model, in *reference pixels*: the
 * pixels of a 1920x1080 screen at 320 dpi (2 px per dp). Multiply by `density / 2` for real
 * pixels. Every constant here was measured with `uiautomator dump`.
 */
object HomeGeometry {
    /** Card base height: 90 dp. */
    const val BASE_CARD_HEIGHT = 180f

    /** Focus zoom per *Focused card size* index (None, XS, S, M, L). */
    val ZOOM = floatArrayOf(1.0f, 1.0f, 1.15f, 1.30f, 1.45f)

    const val CATEGORY_TITLE_HEIGHT = 48f
    const val CATEGORY_TITLE_GAP = 20f
    const val CARD_TITLE_HEIGHT = 47f
    const val FIRST_CARD_INSET = 16f
    const val TITLE_ICON_X = 20f
    const val TITLE_ICON_SIZE = 40f
    const val TITLE_TEXT_X = 80f
    const val TITLE_TEXT_X_NO_ICON = 16f
    const val TITLE_CARD_GAP = 7f

    /** Rows stay this far above the bottom edge when *Top margin* is high. */
    const val BOTTOM_LIMIT = 35f

    /** In the non-fixed modes the first row sits this much higher. */
    const val NON_FIXED_LIFT = 20f

    /** Card height for a category scale, rounded down to an even pixel count. */
    fun cardHeight(categoryScale: Float, cardsSize: Float): Float = evenFloor(BASE_CARD_HEIGHT * categoryScale * cardsSize)

    fun cardWidth(height: Float, ratio: Float): Float = evenFloor(height * ratio)

    fun evenFloor(v: Float): Float = floor((v + 1e-3f) / 2f) * 2f

    /**
     * How far the zoomed focused card reaches past its unzoomed edge, rounded up to a whole
     * pixel: with it the default layout lands exactly on the dumped bounds (row pitch 349).
     */
    fun overhang(cardHeight: Float, zoom: Float): Float = ceil(cardHeight * (zoom - 1f) / 2f - 1e-3f)

    /** The parameters every row shares, read from the settings. */
    data class Params(
        val screenW: Float = 1920f,
        val screenH: Float = 1080f,
        val spacing: Float,
        val zoom: Float,
        val extraRoom: Float,
        val showCategoryTitle: Boolean,
        val showCardTitle: Boolean,
        val rowsSpacing: Float,
        val topMargin: Float,
        val startMargin: Float,
        val endMargin: Float,
        val cardsSize: Float,
        val fixedPosition: Boolean,
        val hideRowsAbove: Boolean,
        val showTitleIcon: Boolean,
    ) {
        val rowLeft: Float get() = startMargin * screenW
        val rowRight: Float get() = endMargin * screenW

        companion object {
            fun from(s: Settings, screenW: Float = 1920f, screenH: Float = 1080f): Params {
                val n = s[Keys.Cards.spacing].toFloat()
                val glow = if (s[Keys.Focus.effect] == 2) s[Keys.Focus.glowSize] / 2f else 0f
                val dock = s[Keys.Categories.dockPadding] * 2f
                return Params(
                    screenW = screenW,
                    screenH = screenH,
                    spacing = n,
                    zoom = ZOOM.getOrElse(s[Keys.Focus.scale]) { 1.3f },
                    extraRoom = max(0f, max(glow, dock) - n),
                    showCategoryTitle = s[Keys.Categories.showTitle],
                    showCardTitle = s[Keys.Cards.showTitle],
                    rowsSpacing = s[Keys.Categories.rowsSpacing],
                    topMargin = s[Keys.Categories.topMargin],
                    startMargin = s[Keys.Categories.startMargin],
                    endMargin = s[Keys.Categories.endMargin],
                    cardsSize = s[Keys.Cards.size],
                    fixedPosition = s[Keys.Categories.fixedPosition],
                    hideRowsAbove = s[Keys.Categories.hideRowsAbove],
                    showTitleIcon = s[Keys.Categories.showIcon],
                )
            }
        }
    }

    /**
     * One row's vertical structure, relative to the start of its block (the top of the gap above
     * its category title).
     *
     * @param lines card lines (1 for a row, more for a grid).
     */
    data class Block(
        val cardHeight: Float,
        val lines: Int,
        val titleTop: Float?,
        val gridTop: Float,
        val gridHeight: Float,
        val cardTitleTop: Float,
        val height: Float,
        /** Distance to the next block's start. */
        val pitch: Float,
    ) {
        val gridBottom: Float get() = gridTop + gridHeight

        /** Top of the first line of cards. */
        fun cardTop(line: Int, spacing: Float): Float = gridTop + 2 * spacing + line * (cardHeight + 2 * spacing)
    }

    fun block(p: Params, cardHeight: Float, lines: Int = 1): Block {
        val n = p.spacing
        val over = overhang(cardHeight, p.zoom)
        val titleTop = if (p.showCategoryTitle) CATEGORY_TITLE_GAP else null
        val titleBottom = if (p.showCategoryTitle) CATEGORY_TITLE_GAP + CATEGORY_TITLE_HEIGHT else 0f
        val gridTop = titleBottom + p.extraRoom + TITLE_CARD_GAP - n + over
        val gridHeight = lines * cardHeight + (lines - 1) * 2 * n + 4 * n
        val cardTitleTop = gridTop + gridHeight - n + over + p.extraRoom + TITLE_CARD_GAP
        val end = cardTitleTop + if (p.showCardTitle) CARD_TITLE_HEIGHT else 0f
        return Block(cardHeight, lines, titleTop, gridTop, gridHeight, cardTitleTop, end, end + p.rowsSpacing * p.screenH)
    }

    /**
     * Where the focused row's grid top goes: *Top margin* of the screen height, kept on screen.
     */
    fun focusedGridTop(p: Params, focused: Block): Float {
        val wanted = p.topMargin * p.screenH
        val limit = p.screenH - focused.gridHeight - BOTTOM_LIMIT
        return minOf(wanted, limit)
    }

    /**
     * Vertical offsets (block starts on screen) for every row.
     *
     * Fixed mode keeps the focused row at the top margin. Otherwise the list starts slightly
     * higher and scrolls only as far as needed to keep the focused row on screen; [previous] are
     * the block starts of the last layout, used to keep rows still.
     */
    fun blockStarts(p: Params, blocks: List<Block>, focused: Int, previous: FloatArray?): FloatArray {
        if (blocks.isEmpty()) return FloatArray(0)
        val f = focused.coerceIn(0, blocks.lastIndex)
        val rel = FloatArray(blocks.size)
        for (i in 1 until blocks.size) rel[i] = rel[i - 1] + blocks[i - 1].pitch
        val targetGrid = focusedGridTop(p, blocks[f])
        val shift: Float = if (p.fixedPosition && !p.hideRowsAbove) {
            targetGrid - (rel[f] + blocks[f].gridTop)
        } else if (p.hideRowsAbove) {
            // Rows above are not drawn; the focused row takes the top position.
            targetGrid - NON_FIXED_LIFT - (rel[f] + blocks[f].gridTop)
        } else {
            val base = targetGrid - NON_FIXED_LIFT - blocks[0].gridTop
            var s = if (previous != null && previous.size == blocks.size) previous[0] else base
            val top = s + rel[f]
            val bottom = top + blocks[f].height
            val minTop = 0f
            val maxBottom = p.screenH - BOTTOM_LIMIT
            if (top < minTop) s += minTop - top
            if (bottom > maxBottom) s -= bottom - maxBottom
            if (f == 0) s = base
            s
        }
        return FloatArray(blocks.size) { rel[it] + shift }
    }

    /** x positions of the cards of a row (left edges), before scrolling. */
    fun cardXs(p: Params, widths: List<Float>): FloatArray {
        val out = FloatArray(widths.size)
        var x = p.rowLeft + FIRST_CARD_INSET
        widths.forEachIndexed { i, w ->
            out[i] = x
            x += w + 2 * p.spacing
        }
        return out
    }

    /**
     * Horizontal scroll of a row.
     * - Sliding row: the focused card stays in the first slot.
     * - Moving selection: scroll only as far as needed to keep the focused card inside the row.
     */
    fun rowScroll(p: Params, xs: FloatArray, widths: List<Float>, focused: Int, moving: Boolean, previous: Float): Float {
        if (xs.isEmpty()) return 0f
        val f = focused.coerceIn(0, xs.lastIndex)
        if (!moving) return xs[f] - xs[0]
        var s = previous
        val left = xs[f] - s
        val right = left + widths[f]
        val minLeft = p.rowLeft + FIRST_CARD_INSET
        val maxRight = p.rowRight - FIRST_CARD_INSET
        if (left < minLeft) s -= minLeft - left
        if (right > maxRight) s += right - maxRight
        return s.coerceAtLeast(0f)
    }

    /** Grid: cards per line that fit, unless the category sets a number. */
    fun gridColumns(p: Params, cardWidth: Float, fixed: Int): Int {
        if (fixed > 0) return fixed
        val avail = p.rowRight - p.rowLeft - 2 * FIRST_CARD_INSET
        return max(1, ((avail + 2 * p.spacing) / (cardWidth + 2 * p.spacing)).toInt())
    }

    /** Grid: lines shown, at most the category's row count (20 when unset) and 12. */
    fun gridLines(itemCount: Int, columns: Int, fixedRows: Int): Int {
        val needed = (itemCount + columns - 1) / columns
        val cap = minOf(if (fixedRows > 0) fixedRows else 20, 12)
        return needed.coerceIn(1, cap)
    }
}
