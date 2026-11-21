/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.displayprofiles

import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

/** The display profiles, one JSON document (`datastore/display_profiles.json`). */
class DisplayProfileStore(
    private val store: DataStore<DisplayProfileList>,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<DisplayProfileList> = store.data
        .stateIn(scope, SharingStarted.Eagerly, runBlocking { store.data.first() })

    val profiles: StateFlow<List<DisplayProfile>> = state
        .map { it.profiles }
        .stateIn(scope, SharingStarted.Eagerly, state.value.profiles)

    fun get(id: String): DisplayProfile? = profiles.value.firstOrNull { it.id == id }

    /** Adds [profile], or replaces the stored one with the same id. */
    suspend fun upsert(profile: DisplayProfile) {
        store.updateData { list ->
            val i = list.profiles.indexOfFirst { it.id == profile.id }
            DisplayProfileList(if (i < 0) list.profiles + profile else list.profiles.toMutableList().also { it[i] = profile })
        }
    }

    suspend fun delete(id: String) {
        store.updateData { list -> DisplayProfileList(list.profiles.filterNot { it.id == id }) }
    }

    suspend fun replaceAll(profiles: List<DisplayProfile>) {
        store.updateData { DisplayProfileList(profiles) }
    }

    /** "Profile #n" with the first n not already used by a profile name. */
    fun nextDefaultName(prefix: String): String {
        val used = profiles.value.map { it.name }.toSet()
        var n = 1
        while ("$prefix$n" in used) n++
        return "$prefix$n"
    }
}
