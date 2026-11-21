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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

@Serializable
data class ItemOverrides(val items: Map<String, ItemOverride> = emptyMap())

/** Per-item titles, icons, parental locks and usage counts. */
class ItemOverrideStore(
    private val store: DataStore<ItemOverrides>,
    private val scope: CoroutineScope,
) {
    val overrides: StateFlow<Map<String, ItemOverride>> = run {
        val initial = runBlocking { store.data.first() }.items
        kotlinx.coroutines.flow.flow { store.data.collect { emit(it.items) } }
            .stateIn(scope, SharingStarted.Eagerly, initial)
    }

    operator fun get(itemKey: String): ItemOverride? = overrides.value[itemKey]

    fun update(itemKey: String, transform: (ItemOverride) -> ItemOverride) {
        scope.launch {
            store.updateData { all ->
                val next = transform(all.items[itemKey] ?: ItemOverride())
                ItemOverrides(if (next == ItemOverride()) all.items - itemKey else all.items + (itemKey to next))
            }
        }
    }

    fun recordLaunch(itemKey: String, now: Long = System.currentTimeMillis()) =
        update(itemKey) { it.copy(launchCount = it.launchCount + 1, lastLaunch = now) }

    suspend fun replaceAll(items: Map<String, ItemOverride>) {
        store.updateData { ItemOverrides(items) }
    }
}
