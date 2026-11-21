/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/** The badges a settings row can carry after its title. */
enum class Badge {
    /** Needs the accessibility service. */
    AccessibilityRequired,

    /** Works better with the accessibility service. */
    AccessibilityRecommended,

    /** Experimental. */
    Experimental,
}

/**
 * One stored setting. The registry ([Keys]) is the single source for the settings UI, reset,
 * export, import and theme files, so a setting cannot exist in one of them and be missing from
 * another.
 *
 * Only values that differ from [default] are written; a missing key means "default", as in
 * Projectivy.
 *
 * @property name QLauncher's storage name, `group.name`.
 * @property projectivyKey the key of the same setting in a Projectivy backup, for import.
 * @property exported false for internal values and per-install identifiers (Projectivy's `nexp`).
 * @property theme true when the value belongs in a theme file (appearance settings).
 */
sealed class SettingKey<T : Any>(
    val name: String,
    val default: T,
    val projectivyKey: String?,
    val exported: Boolean,
    val theme: Boolean,
    val icon: String?,
    val badges: Set<Badge>,
) {
    abstract val prefKey: Preferences.Key<T>

    /**
     * Store the value even when it equals [default]. For settings whose *unset* state means
     * something else (Enable channels follows a permission until it is set).
     */
    open val storeDefault: Boolean = false

    /** Brings a value from storage or an import into range; null when it is unusable. */
    abstract fun coerce(raw: Any?): T?

    override fun toString(): String = name
}

class BoolKey(
    name: String,
    default: Boolean,
    projectivyKey: String? = null,
    exported: Boolean = true,
    theme: Boolean = false,
    icon: String? = null,
    badges: Set<Badge> = emptySet(),
    override val storeDefault: Boolean = false,
) : SettingKey<Boolean>(name, default, projectivyKey, exported, theme, icon, badges) {
    override val prefKey = booleanPreferencesKey(name)
    override fun coerce(raw: Any?): Boolean? = when (raw) {
        is Boolean -> raw
        is String -> raw.toBooleanStrictOrNull()
        else -> null
    }
}

/** An integer setting, usually a slider. */
class IntKey(
    name: String,
    default: Int,
    val min: Int = Int.MIN_VALUE,
    val max: Int = Int.MAX_VALUE,
    val step: Int = 1,
    projectivyKey: String? = null,
    exported: Boolean = true,
    theme: Boolean = false,
    icon: String? = null,
    badges: Set<Badge> = emptySet(),
) : SettingKey<Int>(name, default, projectivyKey, exported, theme, icon, badges) {
    override val prefKey = intPreferencesKey(name)
    override fun coerce(raw: Any?): Int? {
        val v = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull()
            else -> null
        } ?: return null
        return v.toInt().coerceIn(min, max)
    }
}

/** A fractional setting, usually a slider shown as a percentage. */
class FloatKey(
    name: String,
    default: Float,
    val min: Float,
    val max: Float,
    val step: Float,
    projectivyKey: String? = null,
    exported: Boolean = true,
    theme: Boolean = false,
    icon: String? = null,
    badges: Set<Badge> = emptySet(),
) : SettingKey<Float>(name, default, projectivyKey, exported, theme, icon, badges) {
    override val prefKey = floatPreferencesKey(name)
    override fun coerce(raw: Any?): Float? {
        val v = when (raw) {
            is Number -> raw.toFloat()
            is String -> raw.toFloatOrNull()
            else -> null
        } ?: return null
        if (v.isNaN()) return null
        return v.coerceIn(min, max)
    }
}

class LongKey(
    name: String,
    default: Long,
    projectivyKey: String? = null,
    exported: Boolean = true,
    theme: Boolean = false,
    icon: String? = null,
    badges: Set<Badge> = emptySet(),
) : SettingKey<Long>(name, default, projectivyKey, exported, theme, icon, badges) {
    override val prefKey = longPreferencesKey(name)
    override fun coerce(raw: Any?): Long? = when (raw) {
        is Number -> raw.toLong()
        is String -> raw.toLongOrNull()
        else -> null
    }
}

class StringKey(
    name: String,
    default: String,
    projectivyKey: String? = null,
    exported: Boolean = true,
    theme: Boolean = false,
    icon: String? = null,
    badges: Set<Badge> = emptySet(),
) : SettingKey<String>(name, default, projectivyKey, exported, theme, icon, badges) {
    override val prefKey = stringPreferencesKey(name)
    override fun coerce(raw: Any?): String? = when (raw) {
        null -> null
        is String -> raw
        else -> raw.toString()
    }
}

/**
 * A colour, stored as ARGB. Two values are special, as in Projectivy:
 * - [UNSET] (-1) means "not chosen": the component's own default applies.
 * - Any other value whose lowest bit is 1 is "dynamic": it is replaced by a tone of the current
 *   wallpaper palette with the same lightness, keeping its alpha. See `DynamicPalette`.
 */
class ColorKey(
    name: String,
    default: Int = UNSET,
    projectivyKey: String? = null,
    exported: Boolean = true,
    theme: Boolean = false,
    icon: String? = "palette",
    badges: Set<Badge> = emptySet(),
) : SettingKey<Int>(name, default, projectivyKey, exported, theme, icon, badges) {
    override val prefKey = intPreferencesKey(name)
    override fun coerce(raw: Any?): Int? = when (raw) {
        is Number -> raw.toInt()
        is String -> raw.toIntOrNull()
        else -> null
    }

    companion object {
        const val UNSET: Int = -1
    }
}

/** One of a fixed list of options, stored as the option's index. */
class ChoiceKey(
    name: String,
    default: Int,
    val count: Int,
    projectivyKey: String? = null,
    exported: Boolean = true,
    theme: Boolean = false,
    icon: String? = null,
    badges: Set<Badge> = emptySet(),
) : SettingKey<Int>(name, default, projectivyKey, exported, theme, icon, badges) {
    override val prefKey = intPreferencesKey(name)
    override fun coerce(raw: Any?): Int? {
        val v = when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        } ?: return null
        return v.takeIf { it in 0 until count }
    }
}
