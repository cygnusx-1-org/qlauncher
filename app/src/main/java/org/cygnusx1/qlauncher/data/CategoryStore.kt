/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.data

import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

@Serializable
data class CategoryList(
    val categories: List<Category> = DefaultCategories.create(),
    /** Ids handed out to user categories so far; the next one is max(this, 22) + 1. */
    val lastUserId: Int = CategoryIds.FIRST_USER - 1,
    val lastChannelId: Int = CategoryIds.FIRST_CHANNEL - 1,
)

/**
 * The ordered category list. Every change is one atomic transform of the whole list, which is how
 * Projectivy stores it too.
 */
class CategoryStore(
    private val store: DataStore<CategoryList>,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<CategoryList> = store.data
        .stateIn(scope, SharingStarted.Eagerly, runBlocking { store.data.first() })

    val categories: StateFlow<List<Category>> = state
        .map { it.categories }
        .stateIn(scope, SharingStarted.Eagerly, state.value.categories)

    fun get(id: Int): Category? = categories.value.firstOrNull { it.id == id }

    suspend fun edit(transform: (CategoryList) -> CategoryList) {
        store.updateData(transform)
    }

    fun launchEdit(transform: (CategoryList) -> CategoryList) {
        scope.launch { edit(transform) }
    }

    /** Applies [transform] to the category with [id], if it exists. */
    fun update(id: Int, transform: (Category) -> Category) = launchEdit { list ->
        list.copy(categories = list.categories.map { if (it.id == id) transform(it) else it })
    }

    /** Creates "New category" first in the list and returns its id. */
    suspend fun createUserCategory(title: String): Int {
        var newId = 0
        edit { list ->
            val maxExisting = list.categories.maxOfOrNull { if (it.id < CategoryIds.FIRST_CHANNEL) it.id else 0 } ?: 0
            newId = maxOf(list.lastUserId, maxExisting, CategoryIds.FIRST_USER - 1) + 1
            list.copy(
                categories = listOf(Category(id = newId, type = CategoryType.MANUAL, title = title)) + list.categories,
                lastUserId = newId,
            )
        }
        return newId
    }

    fun delete(id: Int) = launchEdit { list -> list.copy(categories = list.categories.filterNot { it.id == id }) }

    /** Moves the category with [id] by [delta] places. */
    fun move(id: Int, delta: Int) = launchEdit { list ->
        val cats = list.categories.toMutableList()
        val from = cats.indexOfFirst { it.id == id }
        if (from < 0) return@launchEdit list
        val to = (from + delta).coerceIn(0, cats.lastIndex)
        if (to == from) return@launchEdit list
        val c = cats.removeAt(from)
        cats.add(to, c)
        list.copy(categories = cats)
    }

    /** Moves the category with [id] to sit at [index] in the full list. */
    fun moveTo(id: Int, index: Int) = launchEdit { list ->
        val cats = list.categories.toMutableList()
        val from = cats.indexOfFirst { it.id == id }
        if (from < 0) return@launchEdit list
        val c = cats.removeAt(from)
        cats.add(index.coerceIn(0, cats.size), c)
        list.copy(categories = cats)
    }

    suspend fun replaceAll(list: CategoryList) {
        store.updateData { list }
    }
}
