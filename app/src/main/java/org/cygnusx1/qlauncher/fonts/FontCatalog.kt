/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.fonts

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/** Where a font in the *Choose from list* picker comes from. */
enum class FontSource { Default, System, Google }

/**
 * One entry of the *Choose from list* picker.
 *
 * @property name the value stored in `Keys.Font.name`.
 * @property label what the list shows; Google families carry a link mark, as in Projectivy.
 */
data class FontEntry(val name: String, val label: String, val source: FontSource)

/** The font list: Default, the system families, then every Google Fonts family. */
object FontCatalog {
    /** The stored name of the bundled DM Sans. */
    const val DEFAULT = "default"

    /** The Android system families, in Projectivy's order. */
    val SYSTEM_FAMILIES: List<String> = listOf(
        "casual",
        "cursive",
        "monospace",
        "sans-serif-thin",
        "sans-serif-light",
        "sans-serif",
        "sans-serif-medium",
        "sans-serif-black",
        "sans-serif-condensed-light",
        "sans-serif-condensed",
        "sans-serif-smallcaps",
        "serif",
        "serif-monospace",
    )

    /** The bundled list of Google Fonts family names. */
    const val GOOGLE_FONTS_ASSET = "backup_google_fonts.txt"

    /** Marks a Google Fonts entry in the list. */
    const val GOOGLE_MARK = "🔗 "

    fun isDefault(name: String): Boolean = name.isBlank() || name.equals(DEFAULT, ignoreCase = true)

    /** The system family [name] refers to (case-insensitive), or null. */
    fun systemFamily(name: String): String? = SYSTEM_FAMILIES.firstOrNull { it.equals(name.trim(), ignoreCase = true) }

    /** "sans-serif-thin" → "Sans-serif-thin", as the picker shows it. */
    fun systemLabel(family: String): String = family.replaceFirstChar { it.uppercaseChar() }

    /** Parses the bundled list: one family per line, `#` starts a comment. */
    fun parseGoogleFamilies(input: InputStream): List<String> = input.bufferedReader().useLines { lines ->
        lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
    }

    /** Every entry, in picker order. [defaultLabel] is the localised "Default". */
    fun entries(googleFamilies: List<String>, defaultLabel: String): List<FontEntry> = buildList {
        add(FontEntry(DEFAULT, defaultLabel, FontSource.Default))
        SYSTEM_FAMILIES.forEach { add(FontEntry(it, systemLabel(it), FontSource.System)) }
        googleFamilies.forEach { add(FontEntry(it, GOOGLE_MARK + it, FontSource.Google)) }
    }

    /** Loads the Google list from assets, off the main thread. */
    suspend fun loadGoogleFamilies(context: Context): List<String> = withContext(Dispatchers.IO) {
        runCatching { context.assets.open(GOOGLE_FONTS_ASSET).use(::parseGoogleFamilies) }.getOrDefault(emptyList())
    }
}
