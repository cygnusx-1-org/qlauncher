/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.home

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Region { Rows, StatusBar, Headers }

/** The status-bar buttons, in their order from the left. */
enum class StatusButton { WallpaperInfo, Connectivity, Notifications, Settings }

/** Where focus is on the home screen. One focus target owns all keys; this says what is "focused". */
@Stable
class HomeFocus {
    var region by mutableStateOf(Region.Rows)

    /** Index into the visible rows. */
    var row by mutableIntStateOf(0)

    /** Last focused card per category id. */
    val cards = mutableStateMapOf<Int, Int>()

    var status by mutableStateOf(StatusButton.Settings)

    /** Left-headers selection: 0 the category icon, 1 its settings button, 2 its reorder button. */
    var headerAction by mutableIntStateOf(0)
    var headersOpen by mutableStateOf(false)

    /** Item key of the card being moved, or null. */
    var reorderCard by mutableStateOf<String?>(null)
    var reorderCategory by mutableStateOf(false)

    /** The category being moved in reorder mode. */
    var reorderCategoryId by mutableStateOf<Int?>(null)

    /** The card under a pressed OK key (ripple). */
    var pressed by mutableStateOf<String?>(null)

    fun cardIndex(categoryId: Int): Int = cards[categoryId] ?: 0
}

/** What the home screen asks its host to do. */
sealed interface HomeEvent {
    data class Open(val row: HomeRow, val card: CardItem) : HomeEvent
    data class Menu(val row: HomeRow, val card: CardItem) : HomeEvent
    data class Focused(val row: HomeRow, val card: CardItem) : HomeEvent
    data class Status(val button: StatusButton, val long: Boolean) : HomeEvent
    data class EditCategory(val categoryId: Int) : HomeEvent
    data class CategoryContent(val categoryId: Int) : HomeEvent
    data class MoveCard(val categoryId: Int, val key: String, val delta: Int) : HomeEvent
    data class MoveCategory(val categoryId: Int, val delta: Int) : HomeEvent
    data object OpenRootPanel : HomeEvent
    data object NextWallpaper : HomeEvent
    data object EasterEgg : HomeEvent
    data object Back : HomeEvent
}

/** Recognises the hidden D-pad sequences. */
class KeySequences {
    private val buffer = ArrayDeque<Char>()

    /** Feeds one direction (U, D, L, R); returns the sequence completed, if any. */
    fun feed(c: Char): String? {
        buffer.addLast(c)
        while (buffer.size > 8) buffer.removeFirst()
        val s = buffer.joinToString("")
        return when {
            s.endsWith(SETTINGS) -> SETTINGS.also { buffer.clear() }
            s.endsWith(EASTER_EGG) -> EASTER_EGG.also { buffer.clear() }
            else -> null
        }
    }

    companion object {
        const val SETTINGS = "URDLURDL"
        const val EASTER_EGG = "UUDDLRLR"
    }
}
