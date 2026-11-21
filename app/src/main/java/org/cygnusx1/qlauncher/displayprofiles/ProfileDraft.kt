/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.displayprofiles

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The profile being edited. A new profile exists only here until the editor is left with a
 * change: [commit] stores it then, and a new profile left untouched is never stored. (Projectivy
 * creates "Profile #1" the moment *Add new…* is opened; QLauncher does not, on purpose.)
 *
 * @param existing the stored profile being edited, or null for *Add new…*.
 * @param newName the name a new profile starts with, e.g. "Profile #1".
 */
class ProfileDraft(private val existing: DisplayProfile?, newName: String) {
    private val original: DisplayProfile = existing ?: DisplayProfile(name = newName)
    private val _profile = MutableStateFlow(original)

    /** The values the editor shows. */
    val profile: StateFlow<DisplayProfile> = _profile.asStateFlow()

    val isNew: Boolean get() = existing == null

    /** True when anything differs from what the editor opened with. */
    val isChanged: Boolean get() = _profile.value != original

    fun edit(transform: (DisplayProfile) -> DisplayProfile) {
        _profile.value = transform(_profile.value).copy(id = original.id)
    }

    fun rename(name: String) = edit { it.copy(name = name.trim().ifEmpty { original.name }) }

    fun setPreviewPattern(index: Int) = edit { it.copy(previewPattern = index.coerceIn(0, CalibrationPatterns.all.lastIndex)) }

    /**
     * Called when the editor is left (BACK). Stores the profile when it changed and returns it;
     * returns null when there was nothing to store.
     */
    suspend fun commit(store: DisplayProfileStore): DisplayProfile? {
        if (!isChanged) return null
        val p = _profile.value
        store.upsert(p)
        return p
    }
}
