/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.Settings
import org.cygnusx1.qlauncher.ui.theme.MaterialIcon
import org.cygnusx1.qlauncher.ui.theme.QTheme

/** Reference pixels (1920x1080 at 2 px/dp) to dp. */
internal fun rp(v: Float): Dp = (v / 2f).dp

/** Precomputed layout of the visible rows. */
private class Laid(
    val rows: List<HomeRow>,
    val params: HomeGeometry.Params,
    val heights: List<Float>,
    val widths: List<List<Float>>,
    val columns: List<Int>,
    val blocks: List<HomeGeometry.Block>,
)

private fun layoutRows(rows: List<HomeRow>, p: HomeGeometry.Params): Laid {
    val heights = rows.map { HomeGeometry.cardHeight(it.category.scale, p.cardsSize) }
    val widths = rows.mapIndexed { i, r -> r.items.map { HomeGeometry.cardWidth(heights[i], it.ratio) } }
    val columns = rows.mapIndexed { i, r ->
        if (r.isGrid) HomeGeometry.gridColumns(p, widths[i].maxOrNull() ?: 1f, r.category.maxItemsPerLine) else Int.MAX_VALUE
    }
    val blocks = rows.mapIndexed { i, r ->
        val lines = if (r.isGrid) HomeGeometry.gridLines(r.items.size, columns[i], r.category.maxVisibleRows) else 1
        HomeGeometry.block(p, heights[i], lines)
    }
    return Laid(rows, p, heights, widths, columns, blocks)
}

/**
 * The home screen: category rows on top of the wallpaper (drawn by the host), the status bar and
 * the left headers. With [interactive] false it is the non-focusable live preview.
 */
@Composable
fun HomeScreen(
    rows: List<HomeRow>,
    settings: Settings,
    style: CardStyle,
    focus: HomeFocus,
    status: StatusBarInfo,
    interactive: Boolean,
    onEvent: (HomeEvent) -> Unit,
    modifier: Modifier = Modifier,
    uiAlpha: Float = 1f,
    clockAlpha: Float = 1f,
    images: CardImages? = null,
    mediaPlaying: Boolean = false,
) {
    val conf = LocalConfiguration.current
    val screenW = conf.screenWidthDp * 2f
    val screenH = conf.screenHeightDp * 2f
    val headersEnabled = settings[Keys.LeftHeaders.enabled]
    val railOpen = headersEnabled && focus.headersOpen
    val base = HomeGeometry.Params.from(settings, screenW, screenH)
    val p = if (railOpen) {
        base.copy(showCategoryTitle = false, showCardTitle = false, startMargin = RAIL_OPEN_ROW_LEFT / screenW, cardsSize = base.cardsSize * RAIL_OPEN_SCALE)
    } else {
        base
    }
    val laid = remember(rows, p) { layoutRows(rows, p) }
    // Cards (the zoomed focused card, the card that slid out of the first slot) are cut only at
    // the open rail's edge; with the rail closed they run to the screen edge.
    val rowClipLeft = if (railOpen) RAIL_OPEN_CLIP_X else 0f
    if (rows.isNotEmpty() && focus.row > rows.lastIndex) focus.row = rows.lastIndex

    // Vertical: block starts, animated as one shift.
    val prevStarts = remember { arrayOfNulls<FloatArray>(1) }
    val starts = HomeGeometry.blockStarts(p, laid.blocks, focus.row, prevStarts[0])
    prevStarts[0] = starts
    val shift = remember { Animatable(Float.NaN) }
    val targetShift = starts.firstOrNull() ?: 0f
    LaunchedEffect(targetShift) {
        if (shift.value.isNaN() || !interactive) shift.snapTo(targetShift) else shift.animateTo(targetShift, tween(250))
    }
    val shiftNow = if (shift.value.isNaN()) targetShift else shift.value
    val rel = FloatArray(starts.size) { starts[it] - targetShift }

    // Horizontal scroll per row.
    val scrolls = remember { HashMap<Int, Float>() }

    val fr = remember { FocusRequester() }
    LaunchedEffect(interactive) { if (interactive) runCatching { fr.requestFocus() } }
    val scope = rememberCoroutineScope()
    val longJob = remember { arrayOfNulls<Job>(1) }
    val longFired = remember { BooleanArray(1) }
    val sequences = remember { KeySequences() }
    val playJob = remember { arrayOfNulls<Job>(1) }

    val currentRow = rows.getOrNull(focus.row)
    val currentIndex = currentRow?.let { focus.cardIndex(it.id).coerceIn(0, (it.items.size - 1).coerceAtLeast(0)) } ?: 0
    val currentCard = currentRow?.items?.getOrNull(currentIndex)
    LaunchedEffect(currentRow?.id, currentCard?.key) {
        if (interactive && currentRow != null && currentCard != null) onEvent(HomeEvent.Focused(currentRow, currentCard))
    }

    // In reorder modes focus follows the moving card or category as the stored order changes.
    LaunchedEffect(rows, focus.reorderCard, focus.reorderCategoryId, focus.reorderCategory) {
        val card = focus.reorderCard
        val r = rows.getOrNull(focus.row)
        if (card != null && r != null) {
            val i = r.items.indexOfFirst { it.key == card }
            if (i >= 0) focus.cards[r.id] = i
        }
        val cat = focus.reorderCategoryId
        if (focus.reorderCategory && cat != null) {
            val i = rows.indexOfFirst { it.id == cat }
            if (i >= 0) focus.row = i
        } else if (!focus.reorderCategory) {
            focus.reorderCategoryId = null
        }
    }

    val statusVisible = status.buttons.isNotEmpty() || status.showClock
    val statusShown = !settings[Keys.StatusBar.autohide] || focus.row == 0 || focus.region == Region.StatusBar

    fun setCard(row: HomeRow, i: Int) {
        focus.cards[row.id] = i.coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
    }

    fun moveRow(d: Int) {
        val next = focus.row + d
        if (next !in rows.indices) return
        focus.row = next
        if (!settings[Keys.Categories.rememberFocus]) setCard(rows[next], 0)
    }

    fun handleRows(e: KeyEvent): Boolean {
        val row = currentRow ?: return false
        val idx = currentIndex
        val cols = laid.columns.getOrElse(focus.row) { Int.MAX_VALUE }
        // A grid with a Row count shows fewer cards than it holds; focus stays on drawn cards.
        val limit = if (row.isGrid) minOf(row.items.size, (laid.blocks.getOrNull(focus.row)?.lines ?: 1) * cols) else row.items.size
        val down = e.type == KeyEventType.KeyDown
        when (e.key) {
            Key.DirectionUp -> if (down) {
                if (focus.reorderCard != null) return true
                if (row.isGrid && idx >= cols) setCard(row, idx - cols)
                else if (focus.row == 0) { if (statusVisible && status.buttons.isNotEmpty()) { focus.region = Region.StatusBar; focus.status = status.buttons.last() } }
                else moveRow(-1)
            }
            Key.DirectionDown -> if (down) {
                if (focus.reorderCard != null) return true
                if (row.isGrid && idx + cols < limit) setCard(row, idx + cols)
                else if (row.isGrid && (idx / cols) < (limit - 1) / cols) setCard(row, limit - 1)
                else moveRow(1)
            }
            Key.DirectionLeft -> if (down) {
                if (focus.reorderCard != null) {
                    onEvent(HomeEvent.MoveCard(row.id, focus.reorderCard!!, -1))
                } else if (idx > 0 && !(row.isGrid && idx % cols == 0)) setCard(row, idx - 1)
                else if (settings[Keys.LeftHeaders.enabled]) {
                    focus.headersOpen = true; focus.region = Region.Headers; focus.headerAction = 0
                }
            }
            Key.DirectionRight -> if (down) {
                if (focus.reorderCard != null) {
                    onEvent(HomeEvent.MoveCard(row.id, focus.reorderCard!!, 1))
                } else if (idx < limit - 1 && !(row.isGrid && idx % cols == cols - 1)) setCard(row, idx + 1)
            }
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                val card = currentCard ?: return true
                if (down && e.nativeKeyEvent.repeatCount > 0 && !longFired[0] && focus.reorderCard == null) {
                    // An auto-repeated OK means the key is held: long press now.
                    longJob[0]?.cancel()
                    longFired[0] = true
                    focus.pressed = null
                    onEvent(HomeEvent.Menu(row, card))
                } else if (down && e.nativeKeyEvent.repeatCount == 0) {
                    if (focus.reorderCard != null) return true
                    focus.pressed = card.key
                    longFired[0] = false
                    longJob[0]?.cancel()
                    longJob[0] = scope.launch {
                        delay(LONG_PRESS_MS)
                        longFired[0] = true
                        focus.pressed = null
                        onEvent(HomeEvent.Menu(row, card))
                    }
                } else if (e.type == KeyEventType.KeyUp) {
                    longJob[0]?.cancel()
                    focus.pressed = null
                    if (focus.reorderCard != null) focus.reorderCard = null
                    else if (!longFired[0]) onEvent(HomeEvent.Open(row, card))
                    longFired[0] = false
                }
            }
            else -> return false
        }
        return true
    }

    fun handleStatus(e: KeyEvent): Boolean {
        val down = e.type == KeyEventType.KeyDown
        val buttons = status.buttons
        val i = buttons.indexOf(focus.status).coerceAtLeast(0)
        when (e.key) {
            Key.DirectionDown -> if (down) focus.region = Region.Rows
            Key.DirectionUp -> Unit
            Key.DirectionLeft -> if (down && i > 0) focus.status = buttons[i - 1]
            Key.DirectionRight -> if (down && i < buttons.lastIndex) focus.status = buttons[i + 1]
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                if (down && e.nativeKeyEvent.repeatCount > 0 && !longFired[0]) {
                    longJob[0]?.cancel()
                    longFired[0] = true
                    onEvent(HomeEvent.Status(focus.status, long = true))
                } else if (down && e.nativeKeyEvent.repeatCount == 0) {
                    longFired[0] = false
                    longJob[0]?.cancel()
                    longJob[0] = scope.launch {
                        delay(LONG_PRESS_MS)
                        longFired[0] = true
                        onEvent(HomeEvent.Status(focus.status, long = true))
                    }
                } else if (e.type == KeyEventType.KeyUp) {
                    longJob[0]?.cancel()
                    if (!longFired[0]) onEvent(HomeEvent.Status(focus.status, long = false))
                    longFired[0] = false
                }
            }
            else -> return false
        }
        return true
    }

    fun handleHeaders(e: KeyEvent): Boolean {
        val down = e.type == KeyEventType.KeyDown
        val row = currentRow ?: return false
        val actions = buildList {
            add(0)
            if (settings[Keys.LeftHeaders.showSettings]) add(1)
            if (settings[Keys.LeftHeaders.showReorder]) add(2)
        }
        when (e.key) {
            Key.DirectionUp -> if (down) {
                val moving = focus.reorderCategoryId
                if (focus.reorderCategory && moving != null) onEvent(HomeEvent.MoveCategory(moving, -1)) else moveRow(-1)
            }
            Key.DirectionDown -> if (down) {
                val moving = focus.reorderCategoryId
                if (focus.reorderCategory && moving != null) onEvent(HomeEvent.MoveCategory(moving, 1)) else moveRow(1)
            }
            Key.DirectionLeft -> if (down && !focus.reorderCategory) {
                val pos = actions.indexOf(focus.headerAction)
                if (pos < actions.lastIndex) focus.headerAction = actions[pos + 1]
            }
            Key.DirectionRight -> if (down && !focus.reorderCategory) {
                val pos = actions.indexOf(focus.headerAction)
                if (pos > 0) focus.headerAction = actions[pos - 1]
                else { focus.headersOpen = false; focus.region = Region.Rows }
            }
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (down) {
                if (focus.headerAction == 1 && !focus.reorderCategory) {
                    if (e.nativeKeyEvent.repeatCount == 0) {
                        longFired[0] = false
                        longJob[0]?.cancel()
                        longJob[0] = scope.launch {
                            delay(LONG_PRESS_MS)
                            longFired[0] = true
                            onEvent(HomeEvent.CategoryContent(row.id))
                        }
                    } else if (!longFired[0]) {
                        longJob[0]?.cancel()
                        longFired[0] = true
                        onEvent(HomeEvent.CategoryContent(row.id))
                    }
                }
            } else if (e.type == KeyEventType.KeyUp) {
                longJob[0]?.cancel()
                val wasLong = longFired[0]
                longFired[0] = false
                when {
                    wasLong -> Unit
                    focus.reorderCategory -> focus.reorderCategory = false
                    focus.headerAction == 1 -> onEvent(HomeEvent.EditCategory(row.id))
                    focus.headerAction == 2 -> {
                        focus.reorderCategory = true
                        focus.reorderCategoryId = row.id
                    }
                    else -> { focus.headersOpen = false; focus.region = Region.Rows }
                }
            }
            else -> return false
        }
        return true
    }

    fun onKey(e: KeyEvent): Boolean {
        if (!interactive) return false
        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
            val c = when (e.key) {
                Key.DirectionUp -> 'U'
                Key.DirectionDown -> 'D'
                Key.DirectionLeft -> 'L'
                Key.DirectionRight -> 'R'
                else -> null
            }
            if (c != null) {
                when (sequences.feed(c)) {
                    KeySequences.SETTINGS -> onEvent(HomeEvent.OpenRootPanel)
                    KeySequences.EASTER_EGG -> onEvent(HomeEvent.EasterEgg)
                }
            }
        }
        when (e.key) {
            Key.Back, Key.Escape -> {
                if (e.type != KeyEventType.KeyUp) return true
                when {
                    focus.reorderCard != null -> focus.reorderCard = null
                    focus.reorderCategory -> focus.reorderCategory = false
                    focus.region == Region.Headers -> { focus.headersOpen = false; focus.region = Region.Rows }
                    focus.region == Region.StatusBar -> focus.region = Region.Rows
                    else -> onEvent(HomeEvent.Back)
                }
                return true
            }
            Key.Menu -> {
                if (e.type == KeyEventType.KeyUp && focus.region != Region.Rows) onEvent(HomeEvent.OpenRootPanel)
                return true
            }
            Key.MediaPlayPause -> {
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
                    playJob[0]?.cancel()
                    playJob[0] = scope.launch {
                        delay(LONG_PRESS_MS)
                        onEvent(HomeEvent.NextWallpaper)
                    }
                } else if (e.type == KeyEventType.KeyUp) {
                    playJob[0]?.cancel()
                }
                return false
            }
        }
        return when (focus.region) {
            Region.Rows -> handleRows(e)
            Region.StatusBar -> handleStatus(e)
            Region.Headers -> handleHeaders(e)
        }
    }

    val unselected = settings[Keys.Categories.unselectedOpacity]
    val hideAbove = p.hideRowsAbove
    val headerRegion = focus.region == Region.Headers

    Box(
        modifier
            .fillMaxSize()
            .then(if (interactive) Modifier.focusRequester(fr).onKeyEvent { onKey(it) }.focusable() else Modifier)
            .testTag("home"),
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = uiAlpha }) {
            laid.rows.forEachIndexed { i, row ->
                val top = rel[i] + shiftNow
                val onScreen = !(top + laid.blocks[i].height < -screenH / 2 || top > screenH * 1.5f)
                if (onScreen) {
                    key(row.id) {
                        HomeRowLayer(
                            row, laid, i, top, p, rowClipLeft, focus, hideAbove, unselected, scrolls, interactive, headerRegion,
                            style, images, mediaPlaying,
                        )
                    }
                }
            }
        }
        if (statusVisible) {
            StatusBar(
                status, settings, focus.region == Region.StatusBar, focus.status,
                visible = statusShown,
                alpha = uiAlpha,
                clockAlpha = clockAlpha,
                screenW = screenW,
            )
        }
        if (headersEnabled) {
            LeftHeaders(
                rows, focus, settings, open = railOpen, interactive = interactive,
                focusedGridCenter = rel.getOrNull(focus.row)?.let { it + shiftNow + (laid.blocks.getOrNull(focus.row)?.let { b -> b.gridTop + b.gridHeight / 2 } ?: 0f) } ?: 0f,
                alpha = uiAlpha,
            )
        }
    }
}

@Composable
private fun HomeRowLayer(
    row: HomeRow,
    laid: Laid,
    i: Int,
    top: Float,
    p: HomeGeometry.Params,
    rowClipX: Float,
    focus: HomeFocus,
    hideAbove: Boolean,
    unselected: Float,
    scrolls: HashMap<Int, Float>,
    interactive: Boolean,
    headerRegion: Boolean,
    style: CardStyle,
    images: CardImages?,
    mediaPlaying: Boolean,
) {
    val isFocusedRow = i == focus.row
    val rowAlphaTarget = when {
        hideAbove && i < focus.row -> 0f
        isFocusedRow -> 1f
        else -> unselected
    }
    val rowAlpha by animateFloatAsState(rowAlphaTarget, tween(if (interactive) 250 else 0), label = "rowAlpha")
    val xs = HomeGeometry.cardXs(p, laid.widths[i])
    val cols = laid.columns[i]
    val idx = focus.cardIndex(row.id).coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
    val target = if (row.isGrid) 0f else HomeGeometry.rowScroll(p, xs, laid.widths[i], idx, row.category.hasMovingSelection, scrolls[row.id] ?: 0f)
    scrolls[row.id] = target
    val anim = remember { Animatable(target) }
    LaunchedEffect(target) {
        if (interactive) anim.animateTo(target, tween(250)) else anim.snapTo(target)
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = rowAlpha }) {
        RowView(
            row, laid, i, top, xs, cols, anim.value, idx, rowClipX,
            focusedRow = isFocusedRow && (focus.region == Region.Rows || headerRegion),
            cardFocused = isFocusedRow && focus.region == Region.Rows,
            style = style, focus = focus, interactive = interactive, images = images, mediaPlaying = mediaPlaying,
        )
    }
}

@Composable
private fun BoxScope.RowView(
    row: HomeRow,
    laid: Laid,
    i: Int,
    top: Float,
    xs: FloatArray,
    cols: Int,
    scroll: Float,
    focusedIndex: Int,
    rowClipX: Float,
    focusedRow: Boolean,
    cardFocused: Boolean,
    style: CardStyle,
    focus: HomeFocus,
    interactive: Boolean,
    images: CardImages?,
    mediaPlaying: Boolean,
) {
    val p = laid.params
    val b = laid.blocks[i]
    val h = laid.heights[i]
    // Category title.
    if (b.titleTop != null) {
        val showIcon = p.showTitleIcon
        Row(
            Modifier
                .offset(rp(p.rowLeft + if (showIcon) HomeGeometry.TITLE_ICON_X else HomeGeometry.TITLE_TEXT_X_NO_ICON), rp(top + b.titleTop))
                .height(rp(HomeGeometry.CATEGORY_TITLE_HEIGHT))
                .testTag("row_title_" + row.id),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showIcon) {
                CategoryIcon(row, rp(HomeGeometry.TITLE_ICON_SIZE), Color.White)
                Spacer(Modifier.width(rp(HomeGeometry.TITLE_TEXT_X - HomeGeometry.TITLE_ICON_X - HomeGeometry.TITLE_ICON_SIZE)))
            }
            Text(row.title, color = Color.White, style = QTheme.type.rowHeader, maxLines = 1)
        }
    }
    // Grid background.
    if (row.isGrid && row.category.backgroundColor != null) {
        Box(
            Modifier
                .offset(rp(p.rowLeft), rp(top + b.gridTop))
                .size(rp(p.rowRight - p.rowLeft), rp(b.gridHeight))
                .background(Color(row.category.backgroundColor), RoundedCornerShape(rp(h * style.corner / 2 + 2 * p.spacing))),
        )
    }
    Box(
        Modifier
            .fillMaxSize()
            .then(if (rowClipX > 0f) Modifier.clipLeft(rowClipX) else Modifier),
    ) {
        row.items.forEachIndexed { j, card ->
            val w = laid.widths[i][j]
            val line = if (row.isGrid) j / cols else 0
            val col = if (row.isGrid) j % cols else j
            val x = if (row.isGrid) xs[0] + col * (w + 2 * p.spacing) else xs[j] - scroll
            val drawn = !(row.isGrid && line >= b.lines) && !(x > p.screenW + 50 || x + w < -50)
            if (drawn) key(card.key) {
            val y = top + b.cardTop(line, p.spacing)
            val isFocused = cardFocused && j == focusedIndex
            HomeCard(
                card, rp(w), rp(h), isFocused, style,
                pressed = focus.pressed == card.key,
                animate = interactive,
                mediaPlaying = mediaPlaying,
                modifier = Modifier
                    .offset(rp(x), rp(y))
                    .then(if (focus.reorderCard == card.key) Modifier.graphicsLayer { translationY = -8f } else Modifier),
            )
        } }
    }
    // The focused card's title (or a program's info block) under the row.
    if (focusedRow && p.showCardTitle) {
        val card = row.items.getOrNull(focusedIndex) ?: return
        val titleX = if (row.isGrid) {
            val col = focusedIndex % cols
            xs[0] + col * (laid.widths[i].getOrElse(focusedIndex) { 0f } + 2 * p.spacing)
        } else {
            p.rowLeft + HomeGeometry.FIRST_CARD_INSET
        }
        if (card.kind == CardKind.Program && card.program != null) {
            ProgramInfo(card, images, rp(titleX), rp(top + b.cardTitleTop))
        } else if (card.title.isNotEmpty()) {
            Text(
                card.title,
                color = Color.White,
                style = QTheme.type.cardTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .offset(rp(titleX), rp(top + b.cardTitleTop))
                    .width(rp(p.rowRight - titleX))
                    .height(rp(HomeGeometry.CARD_TITLE_HEIGHT))
                    .wrapContentHeight(Alignment.CenterVertically, unbounded = true)
                    .testTag("card_title"),
            )
        }
    }
}

@Composable
private fun ProgramInfo(card: CardItem, images: CardImages?, x: Dp, y: Dp) {
    val p = card.program ?: return
    Row(Modifier.offset(x, y)) {
        val banner = images?.app(p.packageName, banner = true, overrideUri = null)
        if (banner != null) {
            AsyncImage(banner, null, Modifier.size(80.dp, 45.dp))
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.width(560.dp)) {
            Text(p.title, color = Color.White, style = QTheme.type.programTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            card.subtitle?.let { Text(it, color = Color(0xFFAAAAAA), style = QTheme.type.programDescription, maxLines = 1) }
            p.description?.let { Text(it, color = Color(0xFFAAAAAA), style = QTheme.type.programDescription, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** A category's icon: its custom picture when it has one, else its Material icon. */
@Composable
internal fun CategoryIcon(row: HomeRow, size: Dp, tint: Color) {
    val custom = row.category.customIconUri
    if (custom != null) {
        AsyncImage(custom, row.title, Modifier.size(size))
    } else {
        MaterialIcon(row.icon, size = size, tint = tint)
    }
}

/** Clips everything left of [x] (reference px): the rows' left edge while the rail is open. */
private fun Modifier.clipLeft(x: Float): Modifier = drawWithContent {
    val left = x / 2f * density
    clipRect(left = left, top = -size.height, right = size.width * 2, bottom = size.height * 2) {
        this@drawWithContent.drawContent()
    }
}

private const val LONG_PRESS_MS = 300L

/** With the rail open the rows start at x = 263 and shrink to 253x142 cards. */
private const val RAIL_OPEN_ROW_LEFT = 263f

/** Where cards are cut while the rail is open (reference px, measured on shield2). */
private const val RAIL_OPEN_CLIP_X = 168f
private const val RAIL_OPEN_SCALE = 142f / 144f
