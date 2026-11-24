/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.ui.theme.QTheme

/** A wallpaper as the gallery shows it. */
data class GalleryItem(val model: Any?, val title: String)

/**
 * *Show wallpapers*: a "Filter…" field, then a two-column grid of 256x144 px thumbnails with the
 * title over the bottom; the focused one has a white glow. OK shows that wallpaper at once.
 */
@Composable
fun WallpaperGallery(
    items: List<GalleryItem>,
    onPick: (Int) -> Unit,
    onBack: () -> Unit,
    title: String? = null,
    /** How a picture sits in its 16:9 thumbnail: cut to fill it, or (icons) shown whole. */
    contentScale: ContentScale = ContentScale.Crop,
) {
    var filter by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    /** -1 = the filter field. */
    var focus by remember { mutableIntStateOf(if (items.isEmpty()) -1 else 0) }
    val shown = items.withIndex().filter { filter.isBlank() || it.value.title.contains(filter, ignoreCase = true) }
    if (focus >= shown.size) focus = shown.size - 1
    val fr = remember { FocusRequester() }
    val fieldFr = remember { FocusRequester() }
    LaunchedEffect(editing) { runCatching { if (editing) fieldFr.requestFocus() else fr.requestFocus() } }
    val rows = shown.chunked(2)
    val listState = rememberLazyListState()
    LaunchedEffect(focus) { if (focus >= 0) listState.animateScrollToItem((focus / 2).coerceAtLeast(0)) }

    PanelCard(
        Modifier
            .focusRequester(fr)
            .onKeyEvent { e ->
                if (editing) return@onKeyEvent false
                val down = e.type == KeyEventType.KeyDown
                when (e.key) {
                    Key.DirectionUp -> if (down) focus = if (focus < 2) -1 else focus - 2
                    Key.DirectionDown -> if (down) focus = if (focus < 0) (if (shown.isEmpty()) -1 else 0) else (focus + 2).coerceAtMost(shown.size - 1)
                    Key.DirectionLeft -> if (down && focus > 0 && focus % 2 == 1) focus -= 1
                    Key.DirectionRight -> if (down && focus >= 0 && focus % 2 == 0 && focus + 1 < shown.size) focus += 1
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (e.type == KeyEventType.KeyUp) {
                        if (focus < 0) editing = true else shown.getOrNull(focus)?.let { onPick(it.index) }
                    }
                    Key.Back, Key.Escape -> if (e.type == KeyEventType.KeyUp) onBack()
                    else -> return@onKeyEvent false
                }
                true
            }
            .focusable()
            .testTag("wallpaper_gallery"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title ?: stringResource(R.string.settings_wallpaper), color = QTheme.colors.accent, style = QTheme.type.panelTitle, modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = if (focus < 0) 0.25f else 0.1f))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (filter.isEmpty() && !editing) Text(stringResource(R.string.gallery_filter), color = Color.White.copy(alpha = 0.6f), style = QTheme.type.panelRow)
                BasicTextField(
                    value = filter,
                    onValueChange = { filter = it; focus = -1 },
                    singleLine = true,
                    textStyle = QTheme.type.panelRow.copy(color = Color.White),
                    cursorBrush = SolidColor(QTheme.colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { editing = false }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldFr)
                        .onPreviewKeyEvent { e ->
                            if ((e.key == Key.Back || e.key == Key.DirectionDown) && e.type == KeyEventType.KeyUp) {
                                editing = false
                                true
                            } else {
                                false
                            }
                        },
                    enabled = editing,
                )
            }
            LazyColumn(
                state = listState,
                userScrollEnabled = false,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                itemsIndexed(rows) { r, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEachIndexed { c, item ->
                            val i = r * 2 + c
                            val focused = i == focus
                            Box(
                                Modifier
                                    .size(128.dp, 72.dp)
                                    .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF333333))
                                    .testTag("gallery_$i"),
                            ) {
                                AsyncImage(item.value.model, item.value.title, contentScale = contentScale, modifier = Modifier.size(128.dp, 72.dp))
                                Box(
                                    Modifier
                                        .align(Alignment.BottomStart)
                                        .fillMaxWidth()
                                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                ) {
                                    Text(item.value.title, color = Color.White, style = QTheme.type.badge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
