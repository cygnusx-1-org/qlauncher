/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.settings

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.cygnusx1.qlauncher.settings.ColorKey
import org.cygnusx1.qlauncher.settings.SettingsRepository
import org.cygnusx1.qlauncher.ui.components.StepSlider
import org.cygnusx1.qlauncher.ui.components.dpadStep
import org.cygnusx1.qlauncher.ui.settings.model.LeftPane
import org.cygnusx1.qlauncher.ui.settings.model.Page
import org.cygnusx1.qlauncher.ui.settings.model.Row
import org.cygnusx1.qlauncher.ui.settings.model.RowContext
import org.cygnusx1.qlauncher.ui.theme.QPalette
import org.cygnusx1.qlauncher.ui.theme.QTheme
import java.util.Calendar
import kotlin.math.roundToInt

/** Focus and editing state of one page; kept on the back stack so returning restores focus. */
@Stable
class GuidedPageState(initialFocus: String? = null) {
    var focusedId by mutableStateOf(initialFocus)
    var expandedId by mutableStateOf<String?>(null)
    var optionIndex by mutableIntStateOf(0)
    var editingId by mutableStateOf<String?>(null)
    var bubbleId by mutableStateOf<String?>(null)
    var bubbleStamp by mutableLongStateOf(0L)

    /** Time editing: values per wheel and the focused wheel. */
    var timeWheels by mutableStateOf(intArrayOf())
    var timeWheel by mutableIntStateOf(0)
}

/**
 * One guided settings page: the left pane and the row list. Every row kind is drawn by the same
 * code, and the focused row's centre is kept on the keyline at y = 421 px.
 *
 * Keys: UP/DOWN move (no wrap), LEFT/RIGHT step a slider, OK activates, BACK closes an open
 * dropdown or editor (otherwise it is left to the caller to pop the page).
 */
@Composable
fun GuidedPage(
    page: Page,
    ctx: RowContext,
    repo: SettingsRepository,
    state: GuidedPageState,
    active: Boolean,
    onNavigate: (String) -> Unit,
    onOpenColor: (Row.Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember(page, ctx) { page.rows(ctx).filter { it.visible(ctx) } }
    val focusables = rows.filter { it !is Row.Footer }
    val focusIndex = focusables.indexOfFirst { it.id == state.focusedId }.let { if (it < 0) 0 else it }
    val focused = focusables.getOrNull(focusIndex)
    val listStart = when (page.leftPane) {
        LeftPane.LivePreview, LeftPane.Wallpaper -> GuidedDimens.ListStartPreview
        is LeftPane.Custom -> if (page.leftPane.wide) GuidedDimens.ListStartWide else GuidedDimens.ListStart
        else -> GuidedDimens.ListStart
    }
    val fr = remember { FocusRequester() }
    val context = LocalContext.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    LaunchedEffect(active, state.editingId) {
        if (active && (state.editingId == null || focusables.firstOrNull { it.id == state.editingId } !is Row.TextEntry)) {
            runCatching { fr.requestFocus() }
        }
    }
    LaunchedEffect(state.bubbleStamp) {
        if (state.bubbleId != null) {
            delay(1500)
            state.bubbleId = null
        }
    }

    fun move(delta: Int) {
        val next = (focusIndex + delta).coerceIn(0, (focusables.size - 1).coerceAtLeast(0))
        focusables.getOrNull(next)?.let { state.focusedId = it.id }
    }

    fun activate() {
        val row = focused ?: return
        if (!row.enabled(ctx)) return
        when (row) {
            is Row.Nav -> onNavigate(row.target)
            is Row.Toggle -> {
                val v = row.effectiveValue?.invoke(ctx) ?: ctx.settings[row.key]
                repo.update(row.key, !v)
            }
            is Row.Dropdown -> {
                state.expandedId = row.id
                state.optionIndex = row.selected(ctx).coerceAtLeast(0)
            }
            is Row.Expander -> {
                state.expandedId = row.id
                state.optionIndex = row.initialChild
            }
            is Row.Color -> onOpenColor(row)
            is Row.TextEntry -> state.editingId = row.id
            is Row.Time -> {
                state.timeWheels = TimeEditing.split(ctx.settings[row.key], row.timeOfDay, !DateFormat.is24HourFormat(context))
                state.timeWheel = 0
                state.editingId = row.id
            }
            is Row.Action -> row.onClick()
            is Row.Slider, is Row.Info, is Row.Footer -> Unit
        }
    }

    fun handleExpanded(e: KeyEvent, row: Row): Boolean {
        val options = when (row) {
            is Row.Dropdown -> row.options(ctx).size
            is Row.Expander -> row.children.size
            else -> 0
        }
        when (e.key) {
            Key.DirectionUp -> if (e.type == KeyEventType.KeyDown) state.optionIndex = (state.optionIndex - 1).coerceAtLeast(0)
            Key.DirectionDown -> if (e.type == KeyEventType.KeyDown) state.optionIndex = (state.optionIndex + 1).coerceAtMost(options - 1)
            Key.DirectionLeft, Key.DirectionRight -> Unit
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (e.type == KeyEventType.KeyUp) {
                when (row) {
                    is Row.Dropdown -> {
                        val opt = row.options(ctx).getOrNull(state.optionIndex)
                        if (opt != null && opt.enabled) {
                            state.expandedId = null
                            row.onSelect(state.optionIndex)
                        }
                    }
                    is Row.Expander -> {
                        val child = row.children.getOrNull(state.optionIndex)
                        if (child != null && child.enabled(ctx)) {
                            state.expandedId = null
                            child.onClick()
                        }
                    }
                    else -> Unit
                }
            }
            Key.Back, Key.Escape -> if (e.type == KeyEventType.KeyUp) state.expandedId = null
            else -> return false
        }
        return true
    }

    fun handleTime(e: KeyEvent, row: Row.Time): Boolean {
        val wheels = state.timeWheels
        when (e.key) {
            Key.DirectionUp, Key.DirectionDown -> if (e.type == KeyEventType.KeyDown) {
                val d = if (e.key == Key.DirectionUp) -1 else 1
                state.timeWheels = TimeEditing.spin(wheels, state.timeWheel, d)
            }
            // The wheels sit in a Row, which mirrors in RTL, so the next wheel is to the left there.
            Key.DirectionLeft, Key.DirectionRight -> if (e.type == KeyEventType.KeyDown) {
                state.timeWheel = (state.timeWheel + dpadStep(e.key == Key.DirectionRight, rtl)).coerceAtMost(wheels.size - 1).coerceAtLeast(0)
            }
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (e.type == KeyEventType.KeyUp) {
                repo.update(row.key, TimeEditing.join(wheels, row.timeOfDay))
                state.editingId = null
            }
            Key.Back, Key.Escape -> if (e.type == KeyEventType.KeyUp) state.editingId = null
            else -> return false
        }
        return true
    }

    fun handle(e: KeyEvent): Boolean {
        val expanded = state.expandedId?.let { id -> focusables.firstOrNull { it.id == id } }
        if (expanded != null) return handleExpanded(e, expanded)
        val editingTime = state.editingId?.let { id -> focusables.firstOrNull { it.id == id } } as? Row.Time
        if (editingTime != null) return handleTime(e, editingTime)
        val down = e.type == KeyEventType.KeyDown
        return when (e.key) {
            Key.DirectionUp -> { if (down) move(-1); true }
            Key.DirectionDown -> { if (down) move(1); true }
            Key.DirectionLeft, Key.DirectionRight -> {
                val row = focused
                if (down && row is Row.Slider && row.enabled(ctx)) {
                    SliderValues.step(row, repo, dpadStep(e.key == Key.DirectionRight, rtl))
                    // The bubble shows the value even when the slider is already at its end.
                    state.bubbleId = row.id
                    state.bubbleStamp = System.nanoTime()
                }
                true
            }
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (e.type == KeyEventType.KeyUp) activate(); true }
            else -> false
        }
    }

    Box(modifier.fillMaxSize()) {
        SettingsSlots.Background.current(Modifier.fillMaxSize())
        GuidedLeftPane(page)
        Box(
            Modifier
                .fillMaxHeight()
                .padding(start = GuidedDimens.ScrimStart)
                .fillMaxWidth()
                .background(QPalette.GuidanceScrim),
        )
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = listStart, end = GuidedDimens.ListEndMargin)
                .clipToBounds()
                .focusRequester(fr)
                .onKeyEvent { handle(it) }
                .focusable()
                .testTag("settings_list"),
        ) {
            val expandedRow = state.expandedId?.let { id -> focusables.firstOrNull { it.id == id } }
            AnimatedContent(
                targetState = expandedRow,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                label = "expand",
            ) { exp ->
                if (exp == null) {
                    RowList(rows, focused?.id, ctx, repo, state, active)
                } else {
                    ExpandedList(exp, ctx, state)
                }
            }
        }
    }
}

/** The rows laid out in a column whose offset keeps the focused row's centre on the keyline. */
@Composable
private fun RowList(
    rows: List<Row>,
    focusedId: String?,
    ctx: RowContext,
    repo: SettingsRepository,
    state: GuidedPageState,
    active: Boolean,
) {
    val anim = remember { Animatable(Float.NaN) }
    var target by remember { mutableFloatStateOf(Float.NaN) }
    LaunchedEffect(Unit) {
        snapshotFlow { target }.collect { t ->
            if (t.isNaN()) return@collect
            if (anim.value.isNaN()) anim.snapTo(t) else anim.animateTo(t, tween(200))
        }
    }
    Layout(
        content = {
            rows.forEach { row ->
                RenderRow(row, active && row.id == focusedId, ctx, repo, state)
            }
        },
    ) { measurables, constraints ->
        val gap = GuidedDimens.RowGap.roundToPx()
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0, maxHeight = Int.MAX_VALUE)) }
        val tops = IntArray(placeables.size)
        var y = 0
        placeables.forEachIndexed { i, p ->
            tops[i] = y
            y += p.height + gap
        }
        val fi = rows.indexOfFirst { it.id == focusedId }.let { if (it < 0) rows.indexOfFirst { r -> r !is Row.Footer } else it }
        val keyline = GuidedDimens.KeylineCenter.toPx()
        val t = if (fi >= 0) keyline - (tops[fi] + placeables[fi].height / 2f) else keyline
        target = t
        layout(constraints.maxWidth, constraints.maxHeight) {
            val off = if (anim.value.isNaN()) t else anim.value
            placeables.forEachIndexed { i, p -> p.place(0, (tops[i] + off).roundToInt()) }
        }
    }
}

@Composable
private fun RenderRow(row: Row, focused: Boolean, ctx: RowContext, repo: SettingsRepository, state: GuidedPageState) {
    val enabled = row.enabled(ctx)
    val title = row.title.resolve()
    val tag = "row_" + row.id
    when (row) {
        is Row.Nav -> StandardRow(title, row.badges, row.description?.invoke(ctx)?.resolve(), row.icon, focused, enabled, Trailing.Chevron, tag)
        is Row.Toggle -> {
            val checked = row.effectiveValue?.invoke(ctx) ?: ctx.settings[row.key]
            StandardRow(
                title, row.badges, row.description?.invoke(ctx)?.resolve(), row.icon, focused, enabled, Trailing.None, tag,
                minHeight = GuidedDimens.ToggleMinHeight,
                endInset = GuidedDimens.TitleEndSwitch,
            ) {
                SettingsSwitch(
                    checked,
                    enabled,
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = GuidedDimens.SwitchEnd)
                        .testTag(tag + "_switch"),
                )
            }
        }
        is Row.Slider -> SliderRowView(row, title, focused, enabled, ctx, state, tag)
        is Row.Dropdown -> {
            // A description lambda replaces the selected option's label, even when it gives none.
            val desc = if (row.description != null) {
                row.description.invoke(ctx)?.resolve()
            } else {
                row.options(ctx).getOrNull(row.selected(ctx))?.let { o -> (o.prefix ?: "") + o.label.resolve() }
            }
            StandardRow(title, row.badges, desc, row.icon, focused, enabled, Trailing.DropDown, tag)
        }
        is Row.Expander -> StandardRow(title, row.badges, row.description?.invoke(ctx)?.resolve(), row.icon, focused, enabled, Trailing.DropDown, tag)
        is Row.Color -> {
            val stored = ctx.settings[row.key]
            val swatch = if (stored == ColorKey.UNSET) null else QTheme.colors.palette.resolve(stored, Color.Transparent)
            StandardRow(title, row.badges, row.description?.invoke(ctx)?.resolve(), row.icon, focused, enabled, Trailing.None, tag, swatch = swatch)
        }
        is Row.TextEntry -> TextRowView(row, title, focused, enabled, ctx, repo, state, tag)
        is Row.Time -> {
            val editing = state.editingId == row.id
            StandardRow(
                title, row.badges, if (editing) null else TimeEditing.format(LocalContext.current, ctx.settings[row.key], row.timeOfDay),
                row.icon, focused, enabled, Trailing.None, tag,
                descriptionContent = if (editing) {
                    { TimeWheels(state.timeWheels, state.timeWheel, row.timeOfDay) }
                } else {
                    null
                },
            )
        }
        is Row.Action -> StandardRow(title, row.badges, row.description?.invoke(ctx)?.resolve(), row.icon, focused, enabled, if (row.chevron) Trailing.Chevron else Trailing.None, tag)
        is Row.Info -> StandardRow(title, row.badges, row.value(ctx)?.resolve(), row.icon, focused, enabled, Trailing.None, tag)
        is Row.Footer -> Box(Modifier.fillMaxWidth().padding(horizontal = GuidedDimens.IconStart, vertical = 8.dp).testTag(tag)) { row.content() }
    }
}

@Composable
private fun SliderRowView(row: Row.Slider, title: String, focused: Boolean, enabled: Boolean, ctx: RowContext, state: GuidedPageState, tag: String) {
    val m = SliderValues.model(row, ctx.settings)
    GuidedRowFrame(focused, GuidedDimens.RowMinHeight, tag = tag) {
        RowIcon(row.icon, enabled)
        Column(Modifier.padding(start = GuidedDimens.TitleStart, top = GuidedDimens.RowPadTop, end = 16.dp)) {
            RowTexts(title, row.badges, null, focused, enabled)
            Spacer(Modifier.height(GuidedDimens.SliderTitleGap))
            Box(
                Modifier
                    .padding(start = GuidedDimens.SliderStart - GuidedDimens.TitleStart, end = GuidedDimens.SliderEnd - 16.dp)
                    .then(if (enabled) Modifier else Modifier.background(Color.Transparent)),
            ) {
                StepSlider(
                    position = m.position,
                    origin = m.originFraction,
                    stops = m.stops,
                    bubble = if (state.bubbleId == row.id && focused) SliderValues.format(row, m.value) else null,
                    modifier = Modifier.testTag(tag + "_slider"),
                    active = if (enabled) QTheme.colors.accent else Color.White.copy(alpha = 0.25f),
                    inactive = if (enabled) QTheme.colors.accentContainer else Color.White.copy(alpha = 0.1f),
                )
            }
            Spacer(Modifier.height(GuidedDimens.SliderPadBottom))
        }
    }
}

@Composable
private fun TextRowView(
    row: Row.TextEntry,
    title: String,
    focused: Boolean,
    enabled: Boolean,
    ctx: RowContext,
    repo: SettingsRepository,
    state: GuidedPageState,
    tag: String,
) {
    val editing = state.editingId == row.id
    val value = row.value?.invoke(ctx) ?: row.key?.let { ctx.settings[it] }.orEmpty()
    val shown = value.ifEmpty { row.emptyText?.resolve().orEmpty() }
    StandardRow(
        title, row.badges, if (editing) null else shown, row.icon, focused, enabled, Trailing.None, tag,
        descriptionContent = if (editing) {
            {
                val fr = remember { FocusRequester() }
                var tfv by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
                val accent = QTheme.colors.accent
                fun commit() {
                    state.editingId = null
                    val v = tfv.text.trim()
                    when {
                        row.onCommit != null -> row.onCommit.invoke(v)
                        row.key != null -> repo.update(row.key, v)
                    }
                }
                BasicTextField(
                    value = tfv,
                    onValueChange = { tfv = it },
                    singleLine = true,
                    textStyle = QTheme.type.rowDescription.copy(color = Color.White),
                    cursorBrush = SolidColor(accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fr)
                        .testTag(tag + "_field")
                        .onPreviewKeyEvent { e ->
                            when {
                                (e.key == Key.Back || e.key == Key.Escape) && e.type == KeyEventType.KeyUp -> {
                                    state.editingId = null
                                    true
                                }
                                (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) && e.type == KeyEventType.KeyUp -> {
                                    commit()
                                    true
                                }
                                else -> false
                            }
                        }
                        .padding(vertical = 6.dp)
                        .drawBehind {
                            drawLine(accent, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx())
                        },
                )
                LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
            }
        } else {
            null
        },
    )
}

/** Options of an expanded dropdown or expander, under its header row. */
@Composable
private fun ExpandedList(row: Row, ctx: RowContext, state: GuidedPageState) {
    val labels: List<Pair<String, Boolean>> = when (row) {
        is Row.Dropdown -> row.options(ctx).map { (it.prefix.orEmpty() + it.label.resolve()) to it.enabled }
        is Row.Expander -> row.children.map { it.title.resolve() to it.enabled(ctx) }
        else -> emptyList()
    }
    val header: String? = when (row) {
        is Row.Dropdown -> if (row.description != null) {
            row.description.invoke(ctx)?.resolve()
        } else {
            row.options(ctx).getOrNull(row.selected(ctx))?.let { (it.prefix.orEmpty()) + it.label.resolve() }
        }
        is Row.Expander -> row.description?.invoke(ctx)?.resolve()
        else -> null
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (state.optionIndex - 2).coerceAtLeast(0))
    LaunchedEffect(state.optionIndex) {
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val last = visible.lastOrNull()?.index ?: 0
        if (state.optionIndex <= first || state.optionIndex >= last) {
            listState.animateScrollToItem((state.optionIndex - 2).coerceAtLeast(0))
        }
    }
    // The header stays where the row was, centred on the keyline; the options start below it.
    Layout(
        content = {
            StandardRow(row.title.resolve(), row.badges, header, row.icon, false, true, Trailing.DropUp, "row_" + row.id + "_header")
            ExpandedOptions(labels, listState, state)
        },
        modifier = Modifier.fillMaxSize(),
    ) { m, c ->
        val head = m[0].measure(c.copy(minHeight = 0))
        val headTop = (GuidedDimens.KeylineCenter.toPx() - head.height / 2f).roundToInt()
        val optionsTop = headTop + head.height + GuidedDimens.OptionGap.roundToPx()
        val options = m[1].measure(c.copy(minHeight = 0, maxHeight = (c.maxHeight - optionsTop).coerceAtLeast(0)))
        layout(c.maxWidth, c.maxHeight) {
            head.place(0, headTop)
            options.place(0, optionsTop)
        }
    }
}

@Composable
private fun ExpandedOptions(labels: List<Pair<String, Boolean>>, listState: LazyListState, state: GuidedPageState) {
    LazyColumn(
        state = listState,
        userScrollEnabled = false,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(GuidedDimens.OptionGap),
    ) {
        itemsIndexed(labels) { i, (label, enabled) ->
            GuidedRowFrame(i == state.optionIndex, GuidedDimens.OptionHeight, tag = "option_" + i) {
                Text(
                    label,
                    color = RowColors.title(i == state.optionIndex, enabled),
                    style = QTheme.type.rowTitle,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = GuidedDimens.OptionTextStart, end = 16.dp),
                )
            }
        }
    }
}

/** Hour/minute (and AM/PM) wheels: three values visible per wheel, the middle one current. */
@Composable
private fun TimeWheels(values: IntArray, focusedWheel: Int, timeOfDay: Boolean) {
    val ranges = TimeEditing.ranges(values.size)
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        values.forEachIndexed { i, v ->
            if (i == 1) Text(":", color = Color.White, style = QTheme.type.rowTitle, modifier = Modifier.padding(horizontal = 6.dp))
            if (i == 2) Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(44.dp)) {
                val r = ranges[i]
                listOf(-1, 0, 1).forEach { d ->
                    val x = Math.floorMod(v - r.first + d, r.last - r.first + 1) + r.first
                    val label = if (i == 2) (if (x == 0) "AM" else "PM") else "%02d".format(if (i == 0 && values.size == 3 && x == 0) 12 else x)
                    Text(
                        label,
                        color = when {
                            d != 0 -> Color.White.copy(alpha = 0.3f)
                            i == focusedWheel -> QTheme.colors.accent
                            else -> Color.White
                        },
                        style = QTheme.type.rowTitle,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** Splitting, spinning and formatting of time rows. */
object TimeEditing {
    private const val HOUR = 3_600_000L
    private const val MINUTE = 60_000L

    /** Wheels for a value in ms: hours and minutes, plus AM/PM for 12 h times of day. */
    fun split(ms: Long, timeOfDay: Boolean, twelveHour: Boolean): IntArray {
        val h = ((ms / HOUR) % 24).toInt()
        val m = ((ms % HOUR) / MINUTE).toInt()
        return if (timeOfDay && twelveHour) intArrayOf(h % 12, m, if (h >= 12) 1 else 0) else intArrayOf(h, m)
    }

    fun ranges(count: Int): List<IntRange> = if (count == 3) listOf(0..11, 0..59, 0..1) else listOf(0..23, 0..59)

    fun spin(values: IntArray, wheel: Int, delta: Int): IntArray {
        val r = ranges(values.size)[wheel]
        val out = values.copyOf()
        out[wheel] = Math.floorMod(values[wheel] - r.first + delta, r.last - r.first + 1) + r.first
        return out
    }

    fun join(values: IntArray, @Suppress("UNUSED_PARAMETER") timeOfDay: Boolean): Long {
        val h = if (values.size == 3) values[0] % 12 + values[2] * 12 else values[0]
        return h * HOUR + values[1] * MINUTE
    }

    fun format(context: android.content.Context, ms: Long, timeOfDay: Boolean): String {
        val h = ((ms / HOUR) % 24).toInt()
        val m = ((ms % HOUR) / MINUTE).toInt()
        if (!timeOfDay) return "%02d:%02d".format(h, m)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
        }
        return DateFormat.getTimeFormat(context).format(cal.time)
    }
}

/** The left pane: accent tile, breadcrumb, title, description, and the page's extra. */
@Composable
private fun GuidedLeftPane(page: Page) {
    Box(Modifier.width(GuidedDimens.LeftPaneWidth).fillMaxHeight()) {
        when (val lp = page.leftPane) {
            LeftPane.LivePreview -> SettingsSlots.LivePreview.current(Modifier.fillMaxSize())
            LeftPane.Wallpaper -> SettingsSlots.WallpaperPreview.current(Modifier.fillMaxSize())
            is LeftPane.Custom -> lp.content()
            LeftPane.Info, is LeftPane.Qr -> {
                InfoPane(page)
                if (lp is LeftPane.Qr) {
                    SettingsSlots.Qr.current(
                        lp.payload,
                        Modifier
                            .offset(GuidedDimens.QrX, GuidedDimens.QrY)
                            .width(GuidedDimens.QrSize)
                            .height(GuidedDimens.QrSize),
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxScope.InfoPane(page: Page) {
    GuidedInfo(page.icon, page.breadcrumb.resolve(), page.title.resolve(), page.description?.resolve(), page.legend)
}

/**
 * The guided left pane's accent tile, breadcrumb, title and description. Full-screen flows built
 * outside the page framework (backup export and import) use it too, so every guided screen puts
 * these on the same pixels.
 */
@Composable
fun BoxScope.GuidedInfo(icon: String, breadcrumb: String, title: String, description: String?, legend: Boolean = false) {
    val accent = QTheme.colors.accent
    Box(
        Modifier
            .offset(GuidedDimens.TileX, GuidedDimens.TileY)
            .width(GuidedDimens.TileSize)
            .height(GuidedDimens.TileSize)
            .background(accent, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        org.cygnusx1.qlauncher.ui.theme.MaterialIcon(icon, size = 40.dp, tint = Color.Black)
    }
    Text(
        breadcrumb,
        color = Color.White,
        style = QTheme.type.breadcrumb,
        maxLines = 1,
        modifier = Modifier
            .offset(GuidedDimens.TextX, GuidedDimens.BreadcrumbY)
            .height(20.dp)
            .wrapContentHeight(Alignment.CenterVertically, unbounded = true)
            .testTag("page_breadcrumb"),
    )
    Text(
        title,
        color = accent,
        style = QTheme.type.pageTitle,
        maxLines = 1,
        modifier = Modifier
            .offset(GuidedDimens.TextX, GuidedDimens.TitleY)
            .height(23.5.dp)
            .wrapContentHeight(Alignment.CenterVertically, unbounded = true)
            .testTag("page_title"),
    )
    Column(
        Modifier
            .offset(GuidedDimens.TextX, GuidedDimens.DescriptionY)
            .width(GuidedDimens.DescriptionWidth)
            .padding(top = 8.dp),
    ) {
        description?.let {
            Text(it, color = Color.White.copy(alpha = 0.5f), style = QTheme.type.pageDescription, modifier = Modifier.testTag("page_description"))
        }
        if (legend) {
            Spacer(Modifier.height(12.dp))
            BadgeLegend()
        }
    }
}
