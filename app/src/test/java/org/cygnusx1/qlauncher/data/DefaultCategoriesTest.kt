/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultCategoriesTest {
    @Test
    fun orderAndIdsFollowProjectivy() {
        assertEquals(
            listOf(12, 1, 2, 20, 21, 22, 3, 4, 10, 11, 13, 14, 5, 6, 7, 8, 9),
            DefaultCategories.create().map { it.id },
        )
    }

    @Test
    fun onlyFavoritesAndTvAppsStartVisible() {
        val visible = DefaultCategories.create().filter { it.isVisible }.map { it.id }
        assertEquals(listOf(CategoryIds.FAVORITES, CategoryIds.TV_APPS), visible)
    }
}
