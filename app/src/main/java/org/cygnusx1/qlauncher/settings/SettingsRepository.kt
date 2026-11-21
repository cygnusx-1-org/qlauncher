/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** An immutable view of every setting at one moment. */
class Settings(private val prefs: Preferences) {
    operator fun <T : Any> get(key: SettingKey<T>): T = prefs[key.prefKey] ?: key.default

    /** True when the user (or an import) has stored a value for [key]. */
    fun isSet(key: SettingKey<*>): Boolean = prefs.contains(key.prefKey)

    override fun equals(other: Any?): Boolean = other is Settings && other.prefs == prefs
    override fun hashCode(): Int = prefs.hashCode()

    companion object {
        val DEFAULTS = Settings(emptyPreferences())
    }
}

/**
 * Typed access to the settings store. Values equal to their default are removed rather than
 * written, so the store holds only what the user changed, as Projectivy's does.
 */
class SettingsRepository(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    /** The current settings; loaded synchronously once so the first frame is already right. */
    val settings: StateFlow<Settings> = store.data
        .map { Settings(it) }
        .stateIn(scope, SharingStarted.Eagerly, runBlocking { Settings(store.data.first()) })

    val current: Settings get() = settings.value

    fun <T : Any> flow(key: SettingKey<T>): Flow<T> = settings.map { it[key] }.distinctUntilChanged()

    suspend fun <T : Any> set(key: SettingKey<T>, value: T) {
        store.edit { it.put(key, value) }
    }

    /** Fire-and-forget variant for UI callbacks. */
    fun <T : Any> update(key: SettingKey<T>, value: T) {
        scope.launch { set(key, value) }
    }

    suspend fun reset(key: SettingKey<*>) {
        store.edit { it.remove(key.prefKey) }
    }

    /** Clears every stored value, internal ones included (Projectivy's *Reset to default*). */
    suspend fun resetAll() {
        store.edit { it.clear() }
    }

    /**
     * The stored values that an export carries: exported keys that differ from their default,
     * optionally limited to theme (appearance) keys.
     */
    fun exportValues(themeOnly: Boolean = false): Map<String, Any> {
        val s = current
        return Keys.registry
            .filter { it.exported && (!themeOnly || it.theme) && s.isSet(it) }
            .associate { it.name to s[it] }
    }

    /**
     * Replaces values in one atomic edit. Keys not in [values] are left alone unless
     * [clearOthers] is set, in which case every exported key not in the map returns to default.
     */
    suspend fun importValues(values: Map<SettingKey<*>, Any>, clearOthers: Boolean = false, themeOnly: Boolean = false) {
        store.edit { prefs ->
            if (clearOthers) {
                Keys.registry
                    .filter { it.exported && (!themeOnly || it.theme) && it !in values }
                    .forEach { prefs.remove(it.prefKey) }
            }
            for ((key, raw) in values) {
                prefs.putCoerced(key, raw)
            }
        }
    }

    private fun <T : Any> MutablePreferences.put(key: SettingKey<T>, value: T) {
        if (value == key.default && !key.storeDefault) remove(key.prefKey) else this[key.prefKey] = value
    }

    private fun <T : Any> MutablePreferences.putCoerced(key: SettingKey<T>, raw: Any) {
        val v = key.coerce(raw) ?: return
        put(key, v)
    }
}
