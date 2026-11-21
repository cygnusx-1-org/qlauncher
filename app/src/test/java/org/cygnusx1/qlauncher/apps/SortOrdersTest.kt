/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.apps

import org.cygnusx1.qlauncher.data.ItemOverride
import org.cygnusx1.qlauncher.data.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SortOrdersTest {
    private val keys = listOf("c", "a", "B", "d")
    private val titles = mapOf("a" to "apple", "B" to "Banana", "c" to "cherry", "d" to "Date")
    private val usage = mapOf(
        "a" to ItemOverride(launchCount = 5, lastLaunch = 100),
        "B" to ItemOverride(launchCount = 1, lastLaunch = 300),
        "d" to ItemOverride(launchCount = 5, lastLaunch = 200),
    )

    private fun sort(order: Int, seed: Long = 1L, items: List<String> = keys) =
        SortOrders.sort(items, order, seed, { it }, { titles[it] ?: it }, { usage[it] })

    @Test
    fun customKeepsTheGivenOrder() {
        assertEquals(keys, sort(SortOrder.CUSTOM))
    }

    @Test
    fun nameIsCaseInsensitive() {
        assertEquals(listOf("a", "B", "c", "d"), sort(SortOrder.NAME))
    }

    @Test
    fun lastUsedPutsTheMostRecentFirstAndKeepsTheRestInCustomOrder() {
        assertEquals(listOf("B", "d", "a", "c"), sort(SortOrder.LAST_USED))
    }

    @Test
    fun mostUsedIsStableForTies() {
        // a and d tie at 5 launches: custom order (a before d) is kept; never-launched c comes last.
        assertEquals(listOf("a", "d", "B", "c"), sort(SortOrder.MOST_USED))
    }

    @Test
    fun randomDependsOnlyOnTheSeed() {
        val many = (1..30).map { "k$it" }
        val first = sort(SortOrder.RANDOM, seed = 42L, items = many)
        assertEquals(first, sort(SortOrder.RANDOM, seed = 42L, items = many))
        assertEquals(many.toSet(), first.toSet())
        assertNotEquals(first, sort(SortOrder.RANDOM, seed = 43L, items = many))
        assertNotEquals(many, first)
    }

    @Test
    fun randomKeepsTheOthersInPlaceWhenAnItemArrives() {
        val many = (1..20).map { "k$it" }
        val before = sort(SortOrder.RANDOM, seed = 7L, items = many)
        val after = sort(SortOrder.RANDOM, seed = 7L, items = many + "new")
        assertEquals(before, after - "new")
    }

    @Test
    fun sorterReshufflesOneCategoryOnly() {
        val sorter = ItemSorter(sessionSeed = 99L)
        val a = sorter.seedFor(20)
        val b = sorter.seedFor(21)
        assertEquals(a, sorter.seedFor(20))
        sorter.reshuffle(20)
        assertNotEquals(a, sorter.seedFor(20))
        assertEquals(b, sorter.seedFor(21))
    }

    @Test
    fun sorterUsesTheCategorySeed() {
        val sorter = ItemSorter(sessionSeed = 5L)
        val many = (1..15).map { "k$it" }
        val sorted = sorter.sortKeys(many, SortOrder.RANDOM, 3, { it }, { null })
        assertEquals(SortOrders.shuffled(many, sorter.seedFor(3)) { it }, sorted)
    }
}

class CategoryItemsTest {
    @Test
    fun mergeKeepsTheStoredOrderThenAppendsNewItems() {
        val merged = CategoryItems.merge(
            stored = listOf("c", "gone", "a"),
            available = listOf("a", "b", "c", "d"),
            hidden = listOf("d"),
        )
        assertEquals(listOf("c", "a", "b"), merged)
    }

    @Test
    fun mergeDropsDuplicates() {
        assertEquals(listOf("a", "b"), CategoryItems.merge(listOf("a", "a"), listOf("b", "a", "b"), emptyList()))
    }

    @Test
    fun manualListsKeepWhatExists() {
        val items = CategoryItems.manual(listOf("a", "x", "b", "a", "h"), hidden = listOf("h")) { it != "x" }
        assertEquals(listOf("a", "b"), items)
    }
}

class SettingsHandlerTest {
    @Test
    fun thePreferredSystemHandlerWins() {
        val pick = SettingsHandler.pick(
            listOf(
                SettingsHandler("com.launcher.manager", "Settings", isSystem = false),
                SettingsHandler("com.vendor.settings", "Main", isSystem = true),
                SettingsHandler("com.android.tv.settings", "MainSettings", isSystem = true),
            ),
        )
        assertEquals("com.android.tv.settings", pick?.packageName)
    }

    @Test
    fun anySystemHandlerBeatsAThirdPartyOne() {
        val pick = SettingsHandler.pick(
            listOf(
                SettingsHandler("com.launcher.manager", "Settings", isSystem = false),
                SettingsHandler("com.vendor.settings", "Main", isSystem = true),
            ),
        )
        assertEquals("com.vendor.settings", pick?.packageName)
    }

    @Test
    fun aSingleHandlerIsUsedAndSeveralThirdPartyOnesAreNot() {
        assertEquals("x", SettingsHandler.pick(listOf(SettingsHandler("x", "A", false)))?.packageName)
        assertEquals(null, SettingsHandler.pick(listOf(SettingsHandler("x", "A", false), SettingsHandler("y", "B", false))))
        assertEquals(null, SettingsHandler.pick(emptyList()))
    }
}
