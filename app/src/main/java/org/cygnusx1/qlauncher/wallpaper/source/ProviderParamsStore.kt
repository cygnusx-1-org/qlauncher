/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.source

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import org.cygnusx1.qlauncher.settings.WallpaperProviders

/**
 * Each provider's parameter string (Projectivy's `appearance_wallpaper_provider_params_<id>`): the
 * Wikimedia category, the subreddit, the folder URI, the media file, a plugin's preferences.
 *
 * Also remembers the last custom text of the providers with a free-text option, so switching a
 * dropdown to *[Custom …]* brings it back. Those are local and not exported.
 */
class ProviderParamsStore(private val store: DataStore<Preferences>, scope: CoroutineScope) {

    /** Every stored parameter, keyed by provider id. Defaults are not included. */
    val params: StateFlow<Map<String, String>> = store.data
        .map { readParams(it) }
        .stateIn(scope, SharingStarted.Eagerly, runBlocking { readParams(store.data.first()) })

    private fun readParams(prefs: Preferences): Map<String, String> =
        prefs.asMap().mapNotNull { (k, v) -> if (k.name.startsWith(PARAM)) k.name.removePrefix(PARAM) to v.toString() else null }.toMap()

    /** The parameter of [providerId], or its default. */
    fun get(providerId: String): String = params.value[providerId] ?: default(providerId)

    fun flow(providerId: String): Flow<String> = params.map { it[providerId] ?: default(providerId) }.distinctUntilChanged()

    suspend fun set(providerId: String, value: String) {
        store.edit {
            if (value == default(providerId)) it.remove(paramKey(providerId)) else it[paramKey(providerId)] = value
        }
    }

    /** The remembered custom text of [providerId] (Wikimedia search, Reddit subreddits). */
    suspend fun customText(providerId: String): String = store.data.first()[customKey(providerId)].orEmpty()

    suspend fun setCustomText(providerId: String, value: String) {
        store.edit { it[customKey(providerId)] = value }
    }

    /** What a backup carries: provider id → parameter, only values that differ from the default. */
    fun export(): Map<String, String> = params.value.filterValues { it.isNotEmpty() }

    /** Stores imported parameters, keyed by provider id. */
    suspend fun import(values: Map<String, String>, clearOthers: Boolean) {
        store.edit { prefs ->
            if (clearOthers) {
                prefs.asMap().keys.filter { it.name.startsWith(PARAM) }.forEach { prefs.remove(it) }
            }
            for ((id, value) in values) {
                if (id.isBlank()) continue
                if (value == default(id)) prefs.remove(paramKey(id)) else prefs[paramKey(id)] = value
            }
        }
    }

    companion object {
        private const val PARAM = "params_"
        private const val CUSTOM = "custom_"

        private fun paramKey(id: String) = stringPreferencesKey(PARAM + id)
        private fun customKey(id: String) = stringPreferencesKey(CUSTOM + id)

        /** Projectivy's default parameters: Wikimedia *Landscapes*, Reddit *Earth*. */
        fun default(providerId: String): String = when (providerId) {
            WallpaperProviders.WIKIMEDIA -> WikimediaCategory.DEFAULT.name
            WallpaperProviders.REDDIT -> org.cygnusx1.qlauncher.wallpaper.reddit.RedditPresets.DEFAULT.subreddit
            else -> ""
        }
    }
}
