/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings.backup.ui

import android.view.KeyEvent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.focus.focusProperties
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.cygnusx1.qlauncher.ui.settings.GuidedDimens
import org.cygnusx1.qlauncher.ui.settings.GuidedInfo
import org.cygnusx1.qlauncher.ui.settings.SettingsSlots
import org.cygnusx1.qlauncher.ui.theme.MaterialIcon
import org.cygnusx1.qlauncher.ui.theme.QPalette
import org.cygnusx1.qlauncher.ui.theme.QTheme

/*
 * The guided settings page, measured on 1920 × 1080 captures (1 dp = 2 px):
 * left pane up to x = 1120 px (drawn by [GuidedInfo]); right pane darkened from x = 1184, rows from
 * x = 1232 to 1872 with the first row's top at y = 376. One-line rows are 90 px tall at a
 * 106 px pitch, title plus subtitle 97 px, option rows 62 px at 78 px pitch.
 */
private val ScrimStart = 592.dp
private val ListStart = 616.dp
private val ListWidth = 320.dp
/** Room below the last row so that it too can reach the keyline. */
private val ListBottom = 330.dp
private val RowOneLine = 45.dp
private val RowTwoLine = 48.5.dp
private val RowGap = 8.dp
private val OptionHeight = 31.dp
private val OptionGap = 8.dp

/**
 * A full-screen guided page: breadcrumb, accent title and description on the left, a list of
 * rows on the right. The backdrop and left pane are the settings pages' own ([GuidedInfo]).
 */
@Composable
fun GuidedPage(
    breadcrumb: String,
    title: String,
    description: String,
    icon: String = "settings",
    expansion: GuidedExpansion? = null,
    rows: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    // Back from an open expander: focus returns to the row that opened it.
    val returnTo = remember { mutableStateOf<FocusRequester?>(null) }
    SideEffect { if (expansion != null) returnTo.value = expansion.returnFocus }
    LaunchedEffect(expansion == null) {
        if (expansion == null) {
            val fr = returnTo.value ?: return@LaunchedEffect
            returnTo.value = null
            withFrameNanos { }
            runCatching { fr.requestFocus() }
        }
    }
    Box(Modifier.fillMaxSize()) {
        // The same backdrop, scrim and left pane as every settings page.
        SettingsSlots.Background.current(Modifier.fillMaxSize())
        Box(Modifier.width(GuidedDimens.LeftPaneWidth).fillMaxHeight()) {
            GuidedInfo(icon, breadcrumb, title, description)
        }
        Box(
            Modifier
                .offset(x = ScrimStart)
                .fillMaxHeight()
                .fillMaxWidth()
                .background(QPalette.GuidanceScrim),
        )
        // Right pane: the focused row's centre stays on the settings pages' keyline. The first
        // alignment snaps and the list stays hidden until it is done, so nothing settles.
        val density = LocalDensity.current
        val keyline = with(density) { GuidedDimens.KeylineCenter.toPx() }
        val smallestRow = with(density) { OptionHeight.toPx() }
        val spec = remember(keyline, smallestRow) { KeylineBringIntoViewSpec(keyline, smallestRow) }
        // Shown anyway if no row takes focus.
        LaunchedEffect(spec) {
            delay(300)
            spec.aligned = true
        }
        if (expansion != null) {
            ExpandedPane(expansion)
            return@Box
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .offset(x = ListStart)
                    .width(ListWidth)
                    .fillMaxHeight()
                    .graphicsLayer { alpha = if (spec.aligned) 1f else 0f },
                contentPadding = PaddingValues(top = GuidedDimens.KeylineCenter, bottom = ListBottom),
                verticalArrangement = Arrangement.spacedBy(RowGap),
                content = rows,
            )
        }
    }
}

/**
 * An open expander or confirmation (for example *Replace current setup › Cancel / OK*). As on
 * the settings pages, only its header and its options are shown: the header stays where the row
 * was, centred on the keyline, and the options start 8 px below it. The first option takes focus;
 * when the expansion closes, [returnFocus] gets it back.
 */
class GuidedExpansion(
    val title: String,
    val subtitle: String?,
    val icon: String?,
    val options: List<Pair<String, () -> Unit>>,
    val returnFocus: FocusRequester?,
)

@Composable
private fun ExpandedPane(e: GuidedExpansion) {
    val first = remember { FocusRequester() }
    Layout(
        content = {
            GuidedRow(
                e.title,
                Modifier.focusProperties { canFocus = false },
                subtitle = e.subtitle,
                icon = e.icon,
                end = RowEnd.ExpandLess,
            )
            Column(verticalArrangement = Arrangement.spacedBy(OptionGap)) {
                e.options.forEachIndexed { i, (label, onClick) ->
                    GuidedOptionRow(label, if (i == 0) Modifier.focusRequester(first) else Modifier, onClick)
                }
            }
        },
        modifier = Modifier
            .offset(x = ListStart)
            .width(ListWidth)
            .fillMaxHeight(),
    ) { m, c ->
        val head = m[0].measure(c.copy(minHeight = 0))
        val headTop = (GuidedDimens.KeylineCenter.toPx() - head.height / 2f).roundToInt()
        val optionsTop = headTop + head.height + OptionGap.roundToPx()
        val options = m[1].measure(c.copy(minHeight = 0, maxHeight = (c.maxHeight - optionsTop).coerceAtLeast(0)))
        layout(c.maxWidth, c.maxHeight) {
            head.place(0, headTop)
            options.place(0, optionsTop)
        }
    }
    LaunchedEffect(e.title) { runCatching { first.requestFocus() } }
}

/**
 * Scrolls a focused row so that its centre is on [keyline] (px from the list's top). The first
 * scroll is instant; later ones take 200 ms, as on the settings pages. Requests smaller than
 * [smallestRow] (a text field's cursor) leave the list where it is, so a row does not move when
 * its field is edited.
 */
@Stable
private class KeylineBringIntoViewSpec(private val keyline: Float, private val smallestRow: Float) : BringIntoViewSpec {
    var aligned by mutableStateOf(false)

    override val scrollAnimationSpec: AnimationSpec<Float>
        get() = if (aligned) tween(200) else snap()

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        if (size < smallestRow) return 0f
        val distance = offset + size / 2f - keyline
        if (abs(distance) < 0.5f) aligned = true
        return distance
    }
}

/** What a row shows at its end. */
enum class RowEnd { None, ChevronRight, ExpandMore, ExpandLess }

/**
 * A guided row: icon circle, title and optional subtitle, and an optional chevron. A disabled
 * row keeps its size and focus but is dimmed and ignores OK. The title stops at [titleMaxLines]
 * with an ellipsis; a status message that names a file passes [Int.MAX_VALUE] so the whole path
 * shows and the row grows instead.
 */
@Composable
fun GuidedRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: String? = null,
    end: RowEnd = RowEnd.None,
    enabled: Boolean = true,
    subtitleColor: Color? = null,
    titleMaxLines: Int = 2,
    onClick: () -> Unit = {},
) {
    val height = if (subtitle == null) RowOneLine else RowTwoLine
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(height / 2)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = QPalette.TextPrimary,
            focusedContainerColor = QPalette.GuidedFocusPill,
            focusedContentColor = QPalette.TextPrimary,
            pressedContainerColor = QPalette.GuidedFocusPill,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = height)
                .padding(start = 8.dp, end = GuidedDimens.ChevronCenterFromEnd - GuidedDimens.ChevronSize / 2)
                .alpha(if (enabled) 1f else 0.4f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Box(
                    Modifier
                        .size(GuidedDimens.IconCircle)
                        .background(QPalette.IconCircle, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    MaterialIcon(icon, size = GuidedDimens.IconGlyph, tint = Color.White.copy(alpha = 0.5f))
                }
                Spacer(Modifier.width(12.dp))
            } else {
                Spacer(Modifier.width(44.dp))
            }
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(
                    title,
                    style = QTheme.type.rowTitle,
                    color = if (focused) QPalette.TextPrimary else QPalette.TextTitleUnfocused,
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(subtitle, style = QTheme.type.rowDescription, color = subtitleColor ?: QPalette.TextDescription)
                }
            }
            val mark = when (end) {
                RowEnd.None -> null
                RowEnd.ChevronRight -> "chevron_right"
                RowEnd.ExpandMore -> "arrow_drop_down"
                RowEnd.ExpandLess -> "arrow_drop_up"
            }
            val markStart = GuidedDimens.ChevronCenterFromEnd - GuidedDimens.ChevronSize / 2 + GuidedDimens.ChevronSize
            if (mark == null) {
                Spacer(Modifier.width(16.dp - (GuidedDimens.ChevronCenterFromEnd - GuidedDimens.ChevronSize / 2)))
            } else {
                Spacer(Modifier.width(GuidedDimens.TitleEndChevron - markStart))
                MaterialIcon(mark, size = GuidedDimens.ChevronSize, tint = Color.White.copy(alpha = 0.9f))
            }
        }
    }
}

/** An option under an expanded row: plain text, no icon, 62 px tall. */
@Composable
fun GuidedOptionRow(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(OptionHeight)
            .onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(OptionHeight / 2)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = QPalette.TextPrimary,
            focusedContainerColor = QPalette.GuidedFocusPill,
            focusedContentColor = QPalette.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
    ) {
        Box(Modifier.fillMaxSize().padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
            Text(text, style = QTheme.type.rowTitle, color = if (focused) QPalette.TextPrimary else QPalette.TextTitleUnfocused, maxLines = 1)
        }
    }
}

/**
 * A text entry row for a password: the label as title and an underlined field below. OK on the
 * row opens the keyboard; UP and DOWN leave the field.
 */
@Composable
fun GuidedPasswordRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
) {
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var fieldFocused by remember { mutableStateOf(false) }
    GuidedRowFrame(
        modifier = modifier,
        highlighted = fieldFocused,
        onClick = {
            fieldFocus.requestFocus()
            keyboard?.show()
        },
    ) { rowFocused ->
        Box(Modifier.size(GuidedDimens.IconCircle).background(QPalette.IconCircle, CircleShape), contentAlignment = Alignment.Center) {
            MaterialIcon("password", size = GuidedDimens.IconGlyph, tint = Color.White.copy(alpha = 0.5f))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(label, style = QTheme.type.rowTitle, color = if (rowFocused || fieldFocused) QPalette.TextPrimary else QPalette.TextTitleUnfocused)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = QTheme.type.rowTitle.copy(color = QPalette.TextPrimary),
                cursorBrush = SolidColor(QTheme.colors.accent),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onAny = {
                    keyboard?.hide()
                    onImeAction()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus)
                    .onFocusChanged { fieldFocused = it.isFocused }
                    .onPreviewKeyEvent { e ->
                        val down = e.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                        when (e.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                if (down) focusManager.moveFocus(FocusDirection.Down)
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                if (down) focusManager.moveFocus(FocusDirection.Up)
                                true
                            }
                            else -> false
                        }
                    },
                decorationBox = { inner ->
                    Column {
                        Box(Modifier.padding(vertical = 3.dp)) { inner() }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(if (fieldFocused) QTheme.colors.accent else QPalette.TextDescription))
                    }
                },
            )
        }
    }
}

/** The focus pill and padding of a row, for rows with their own content. */
@Composable
private fun GuidedRowFrame(
    modifier: Modifier,
    highlighted: Boolean,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RowTwoLine)
            .onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(RowTwoLine / 2)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (highlighted) QPalette.GuidedFocusPill else Color.Transparent,
            contentColor = QPalette.TextPrimary,
            focusedContainerColor = QPalette.GuidedFocusPill,
            focusedContentColor = QPalette.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = RowTwoLine).padding(start = 8.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content(focused) }
    }
}
