/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.apps

import android.content.ComponentName
import org.cygnusx1.qlauncher.data.ItemKeys

/** What an item key refers to. */
enum class ItemKind { App, Shortcut, Input, Program, Action, Folder, Unknown }

/**
 * Parsing helpers for the item keys of [ItemKeys], and for the component strings that icon packs
 * and the autostart setting use. Everything here is pure.
 */
object AppKeys {
    private const val SHORTCUT = "shortcut:"
    private const val INPUT = "input:"
    private const val PROGRAM = "program:"
    private const val ACTION = "action:"
    private const val FOLDER = "folder:"

    fun kind(key: String): ItemKind = when {
        ItemKeys.isApp(key) -> ItemKind.App
        key.startsWith(SHORTCUT) -> ItemKind.Shortcut
        key.startsWith(INPUT) -> ItemKind.Input
        key.startsWith(PROGRAM) -> ItemKind.Program
        key.startsWith(ACTION) -> ItemKind.Action
        key.startsWith(FOLDER) -> ItemKind.Folder
        else -> ItemKind.Unknown
    }

    /** The item key of an activity; a class name starting with `.` is resolved against the package. */
    fun app(packageName: String, className: String): String =
        ItemKeys.app(packageName, fullClassName(packageName, className))

    fun of(component: ComponentName): String = app(component.packageName, component.className)

    fun component(key: String): ComponentName? =
        ItemKeys.parseApp(key)?.let { (pkg, cls) -> ComponentName(pkg, cls) }

    /** Package and shortcut id of a shortcut key. The id may itself contain `/`. */
    fun parseShortcut(key: String): Pair<String, String>? {
        if (!key.startsWith(SHORTCUT)) return null
        val body = key.removePrefix(SHORTCUT)
        val slash = body.indexOf('/')
        if (slash <= 0 || slash == body.length - 1) return null
        return body.substring(0, slash) to body.substring(slash + 1)
    }

    /** The package an app or shortcut key belongs to. */
    fun packageOf(key: String): String? =
        ItemKeys.parseApp(key)?.first ?: parseShortcut(key)?.first

    fun inputId(key: String): String? = key.takeIf { it.startsWith(INPUT) }?.removePrefix(INPUT)?.takeIf { it.isNotEmpty() }

    fun programId(key: String): Long? = key.takeIf { it.startsWith(PROGRAM) }?.removePrefix(PROGRAM)?.toLongOrNull()

    fun actionId(key: String): String? = key.takeIf { it.startsWith(ACTION) }?.removePrefix(ACTION)?.takeIf { it.isNotEmpty() }

    fun folderCategoryId(key: String): Int? = key.takeIf { it.startsWith(FOLDER) }?.removePrefix(FOLDER)?.toIntOrNull()

    /** `.Main` in package `a.b` is `a.b.Main`; a full name is returned unchanged. */
    fun fullClassName(packageName: String, className: String): String =
        if (className.startsWith('.')) packageName + className else className

    /**
     * Parses an icon-pack component, `ComponentInfo{pkg/cls}`, also accepting the bare `pkg/cls`
     * form some packs use. Returns the lower-cased lookup key `pkg/cls`, or null when malformed.
     */
    fun appFilterComponent(raw: String): String? {
        var s = raw.trim()
        if (s.startsWith("ComponentInfo{", ignoreCase = true)) {
            s = s.substring("ComponentInfo{".length)
            if (!s.endsWith('}')) return null
            s = s.dropLast(1)
        }
        s = s.trim()
        val slash = s.indexOf('/')
        if (slash <= 0 || slash == s.length - 1) return null
        val pkg = s.substring(0, slash).trim()
        val cls = s.substring(slash + 1).trim()
        if (pkg.isEmpty() || cls.isEmpty() || pkg.any { it.isWhitespace() } || cls.any { it.isWhitespace() }) return null
        return lookupKey(pkg, fullClassName(pkg, cls))
    }

    /** The key icon packs are looked up by: lower-cased package and class. */
    fun lookupKey(packageName: String, className: String): String =
        "${packageName.lowercase()}/${className.lowercase()}"

    /**
     * The value *Autostart on boot* stores for an item: `pkg/activity` for an app, the input key
     * for a TV input, null for anything that cannot autostart.
     */
    fun autostartValue(itemKey: String): String? = when (kind(itemKey)) {
        ItemKind.App -> ItemKeys.parseApp(itemKey)?.let { (p, c) -> "$p/$c" }
        ItemKind.Input -> itemKey
        else -> null
    }

    /** The item key an *Autostart on boot* value refers to, or null for none / screensaver. */
    fun itemKeyOfAutostart(value: String): String? {
        if (value.isEmpty() || value == AUTOSTART_SCREENSAVER) return null
        if (value.startsWith(INPUT)) return value
        val slash = value.indexOf('/')
        if (slash <= 0 || slash == value.length - 1) return null
        return app(value.substring(0, slash), value.substring(slash + 1))
    }

    /** The *Autostart on boot* value for the system screensaver. */
    const val AUTOSTART_SCREENSAVER = "screensaver"
}
