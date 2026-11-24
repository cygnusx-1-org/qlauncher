/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import org.cygnusx1.qlauncher.ui.settings.SettingsSwitch
import org.cygnusx1.qlauncher.ui.theme.MaterialIcon
import org.cygnusx1.qlauncher.ui.theme.QPalette
import org.cygnusx1.qlauncher.ui.theme.QTheme

/** Side-panel geometry: the card is `[1232,32][1872,1048]` px. */
object PanelDimens {
    val Start = 616.dp
    val Top = 16.dp
    val Width = 320.dp
    val Height = 508.dp
    val Corner = 20.dp
    val HeaderHeight = 91.dp
    val Inset = 24.dp
    val RowHeight = 47.dp
    val RowHeightTwoLine = 62.5.dp
    val SectionHeight = 39.5.dp
    val IconCenter = 36.dp
    val Icon = 24.dp
    val TitleStart = 64.dp
    val TitleStartWide = 96.dp
    val Banner = 64.dp
    val BannerHeight = 36.dp
    val BannerRowHeight = 48.dp
}

/** What sits at the end of a panel row. */
@Immutable
sealed interface PanelTrailing {
    data object None : PanelTrailing
    data object Chevron : PanelTrailing
    data class Switch(val checked: Boolean) : PanelTrailing
    data class Hidden(val hidden: Boolean) : PanelTrailing
    data class Text(val text: String) : PanelTrailing
}

/** A row of a side panel. */
@Immutable
sealed interface PanelRow {
    val key: String

    data class Section(override val key: String, val title: String) : PanelRow

    data class Item(
        override val key: String,
        val title: String,
        val subtitle: String? = null,
        val icon: String? = null,
        /** A radio button in place of the icon (Sort, dropdown lists). */
        val radio: Boolean? = null,
        /** A custom leading image (All apps banners); takes the wide title start. */
        val leading: (@Composable () -> Unit)? = null,
        /** False for an icon-sized [leading] that sits where [icon] would. */
        val leadingWide: Boolean = true,
        val trailing: PanelTrailing = PanelTrailing.None,
        val enabled: Boolean = true,
        val onClick: () -> Unit = {},
        val onLongClick: (() -> Unit)? = null,
        /** LEFT/RIGHT on the row (sliders), as [dpadStep]: +1 toward the end edge; return true when handled. */
        val onHorizontal: ((Int) -> Boolean)? = null,
        /** Extra content under the title (a slider). */
        val below: (@Composable (focused: Boolean) -> Unit)? = null,
    ) : PanelRow
}

@Stable
class PanelState(initial: Int = 0) {
    var selected by mutableIntStateOf(initial)
    internal var downAt by mutableLongStateOf(0L)
    internal var longFired = false

    /** OK went down while this panel had focus (so its key-up is ours to act on). */
    internal var downSeen = false
}

/**
 * A side panel sliding in from the right over a dimmed screen. The focused row is a full-width
 * band; [wrap] makes UP/DOWN wrap around (root panel, app menu).
 */
@Composable
fun SidePanel(
    rows: List<PanelRow>,
    state: PanelState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    titleIcon: String? = null,
    header: (@Composable BoxScope.() -> Unit)? = null,
    wrap: Boolean = false,
    tag: String = "side_panel",
) {
    val focusRows = rows.indices.filter { rows[it] is PanelRow.Item }
    if (state.selected !in focusRows) state.selected = focusRows.firstOrNull() ?: 0
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    fun move(d: Int) {
        if (focusRows.isEmpty()) return
        val pos = focusRows.indexOf(state.selected).coerceAtLeast(0)
        val next = pos + d
        state.selected = when {
            next in focusRows.indices -> focusRows[next]
            wrap -> focusRows[Math.floorMod(next, focusRows.size)]
            else -> focusRows[pos]
        }
    }

    fun onKey(e: KeyEvent): Boolean {
        val row = rows.getOrNull(state.selected) as? PanelRow.Item
        val down = e.type == KeyEventType.KeyDown
        return when (e.key) {
            Key.DirectionUp -> { if (down) move(-1); true }
            Key.DirectionDown -> { if (down) move(1); true }
            Key.DirectionLeft, Key.DirectionRight -> {
                if (down && row != null && row.enabled) row.onHorizontal?.invoke(dpadStep(e.key == Key.DirectionRight, rtl))
                true
            }
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                if (row == null || !row.enabled) return true
                if (down) {
                    if (e.nativeKeyEvent.repeatCount == 0) {
                        state.downAt = System.currentTimeMillis()
                        state.longFired = false
                        state.downSeen = true
                    } else if (state.downSeen && !state.longFired && row.onLongClick != null && System.currentTimeMillis() - state.downAt >= 300) {
                        state.longFired = true
                        row.onLongClick.invoke()
                    }
                } else if (e.type == KeyEventType.KeyUp) {
                    // The release of the press that opened this panel is not a click here.
                    if (state.downSeen && !state.longFired) row.onClick()
                    state.longFired = false
                    state.downSeen = false
                }
                true
            }
            Key.Back, Key.Escape -> { if (e.type == KeyEventType.KeyUp) onBack(); true }
            else -> false
        }
    }

    PanelCard(
        modifier
            .focusRequester(fr)
            .onKeyEvent { onKey(it) }
            .focusable()
            .testTag(tag),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (header != null || title != null) {
                Box(Modifier.fillMaxWidth().height(PanelDimens.HeaderHeight)) {
                    if (header != null) {
                        header()
                    } else {
                        Row(
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = PanelDimens.Inset, end = PanelDimens.Inset, bottom = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (titleIcon != null) {
                                MaterialIcon(titleIcon, size = 28.dp, tint = QTheme.colors.accent)
                                Spacer(Modifier.width(12.dp))
                            }
                            Text(title.orEmpty(), color = QTheme.colors.accent, style = QTheme.type.panelTitle, maxLines = 2, modifier = Modifier.testTag(tag + "_title"))
                        }
                    }
                }
            }
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = (state.selected - 3).coerceAtLeast(0))
            LaunchedEffect(state.selected) {
                val visible = listState.layoutInfo.visibleItemsInfo
                val first = visible.firstOrNull()?.index ?: 0
                val last = visible.lastOrNull()?.index ?: 0
                if (state.selected <= first || state.selected >= last) {
                    listState.animateScrollToItem((state.selected - 3).coerceAtLeast(0))
                }
            }
            LazyColumn(state = listState, userScrollEnabled = false, contentPadding = PaddingValues(bottom = 16.dp)) {
                itemsIndexed(rows, key = { _, r -> r.key }) { i, r ->
                    when (r) {
                        is PanelRow.Section -> PanelSectionHeader(r.title)
                        is PanelRow.Item -> PanelItemRow(r, i == state.selected, tag + "_row_" + r.key)
                    }
                }
            }
        }
    }
}

/** The empty panel card at its fixed bounds. */
@Composable
fun PanelCard(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .offset(PanelDimens.Start, PanelDimens.Top)
            .size(PanelDimens.Width, PanelDimens.Height)
            .clip(RoundedCornerShape(PanelDimens.Corner))
            .background(QPalette.PanelBackground),
        content = content,
    )
}

/** Dims the screen behind a panel and slides the panel in from the right (315 ms). */
@Composable
fun PanelHost(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(tween(215)), exit = fadeOut(tween(215))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
    }
    AnimatedVisibility(
        visible,
        enter = slideInHorizontally(tween(315)) { it / 3 } + fadeIn(tween(315)),
        exit = slideOutHorizontally(tween(250)) { it / 3 } + fadeOut(tween(250)),
    ) {
        Box(Modifier.fillMaxSize()) { content() }
    }
}

@Composable
fun PanelSectionHeader(title: String) {
    Box(Modifier.fillMaxWidth().height(PanelDimens.SectionHeight)) {
        Text(
            title,
            color = QTheme.colors.accent,
            style = QTheme.type.panelSection,
            modifier = Modifier.padding(start = PanelDimens.Inset, top = 12.dp),
        )
    }
}

@Composable
private fun PanelItemRow(r: PanelRow.Item, focused: Boolean, tag: String) {
    val alpha = if (r.enabled) 1f else 0.4f
    val wide = (r.leading != null && r.leadingWide) || r.radio != null
    val minH: Dp = when {
        r.leading != null -> PanelDimens.BannerRowHeight
        r.subtitle != null -> PanelDimens.RowHeightTwoLine
        else -> PanelDimens.RowHeight
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = minH)
            .background(if (focused) QPalette.PanelFocusedRow else Color.Transparent)
            .testTag(tag),
    ) {
        when {
            r.leading != null && r.leadingWide -> Box(Modifier.align(Alignment.CenterStart).padding(start = PanelDimens.Inset)) { r.leading.invoke() }
            r.leading != null -> Box(
                Modifier.align(Alignment.CenterStart).padding(start = PanelDimens.IconCenter - 16.dp).size(32.dp),
                contentAlignment = Alignment.Center,
            ) { r.leading.invoke() }
            r.radio != null -> RadioMark(r.radio, Modifier.align(Alignment.CenterStart).padding(start = PanelDimens.Inset + 12.dp))
            r.icon != null -> MaterialIcon(
                r.icon,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = PanelDimens.IconCenter - PanelDimens.Icon / 2),
                size = PanelDimens.Icon,
                tint = Color.White.copy(alpha = 0.85f * alpha),
            )
        }
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .padding(
                    start = if (wide) PanelDimens.TitleStartWide else PanelDimens.TitleStart,
                    end = when (r.trailing) {
                        is PanelTrailing.Switch -> 88.dp
                        PanelTrailing.None -> PanelDimens.Inset
                        else -> 56.dp
                    },
                    top = 8.dp,
                    bottom = 8.dp,
                ),
        ) {
            Text(r.title, color = Color.White.copy(alpha = alpha), style = QTheme.type.panelRow, maxLines = 2)
            if (r.subtitle != null) {
                Text(r.subtitle, color = Color.White.copy(alpha = 0.55f * alpha), style = QTheme.type.rowDescription, maxLines = 2)
            }
            r.below?.invoke(focused)
        }
        when (val t = r.trailing) {
            PanelTrailing.None -> Unit
            PanelTrailing.Chevron -> MaterialIcon("chevron_right", Modifier.align(Alignment.CenterEnd).padding(end = PanelDimens.Inset), 24.dp, Color.White.copy(alpha = 0.8f))
            is PanelTrailing.Switch -> SettingsSwitch(t.checked, r.enabled, Modifier.align(Alignment.CenterEnd).padding(end = PanelDimens.Inset))
            is PanelTrailing.Hidden -> if (t.hidden) {
                MaterialIcon("visibility_off", Modifier.align(Alignment.CenterEnd).padding(end = PanelDimens.Inset), 22.dp, Color.White.copy(alpha = 0.6f))
            }
            is PanelTrailing.Text -> Text(t.text, color = Color.White.copy(alpha = 0.6f), style = QTheme.type.rowDescription, modifier = Modifier.align(Alignment.CenterEnd).padding(end = PanelDimens.Inset))
        }
    }
}

@Composable
private fun RadioMark(selected: Boolean, modifier: Modifier) {
    val accent = QTheme.colors.accent
    Box(
        modifier
            .size(20.dp)
            .background(Color.Transparent, CircleShape)
            .then(Modifier.background(if (selected) accent.copy(alpha = 0.25f) else Color.Transparent, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        MaterialIcon(if (selected) "radio_button_checked" else "radio_button_unchecked", size = 20.dp, tint = if (selected) accent else Color.White.copy(alpha = 0.7f))
    }
}
