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
import android.graphics.Typeface
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.googlefonts.isAvailableOnDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.SettingsRepository
import org.cygnusx1.qlauncher.ui.theme.DmSans
import java.io.File

/** The font settings that select a family. */
data class FontRequest(val name: String, val bold: Boolean, val italic: Boolean) {
    /** 700 with Bold, otherwise 400. */
    val weight: Int get() = if (bold) 700 else 400
}

/**
 * Maps the *Font* settings (`Keys.Font.name`, `bold`, `italic`) to a Compose [FontFamily]:
 *
 * - `default` → DM Sans, the bundled font;
 * - one of the 13 system families → `Typeface.create(name, style)`;
 * - anything else → a Google Fonts family, through the Play services font provider when it is
 *   installed, otherwise downloaded once from the Google Fonts CSS API and cached under
 *   `filesDir/fonts`. DM Sans stands in while the font loads or when it cannot be found.
 *
 * The weight is 700 with Bold and 400 otherwise.
 */
class FontResolver(
    private val context: Context,
    http: OkHttpClient,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
) {
    private val css = GoogleFontsCss(http, File(context.filesDir, "fonts"))

    private val provider = GoogleFont.Provider(
        providerAuthority = "com.google.android.gms.fonts",
        providerPackage = "com.google.android.gms",
        certificates = R.array.backup_gms_fonts_certs,
    )

    @Volatile
    private var providerAvailable: Boolean? = null

    /**
     * The family for the current settings, following every change. Starts as DM Sans; a
     * downloaded Google font replaces it once the file is cached.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val fontFamily: StateFlow<FontFamily> by lazy {
        combine(
            settings.flow(Keys.Font.name),
            settings.flow(Keys.Font.bold),
            settings.flow(Keys.Font.italic),
        ) { name, bold, italic -> FontRequest(name, bold, italic) }
            .distinctUntilChanged()
            .mapLatest { resolve(it) }
            .stateIn(scope, SharingStarted.Eagerly, initialFamily())
    }

    /** What to show before anything is resolved: exact for default and system families. */
    private fun initialFamily(): FontFamily {
        val s = settings.current
        val request = FontRequest(s[Keys.Font.name], s[Keys.Font.bold], s[Keys.Font.italic])
        return when {
            FontCatalog.isDefault(request.name) -> DmSans
            FontCatalog.systemFamily(request.name) != null -> systemFamily(FontCatalog.systemFamily(request.name)!!, request)
            else -> DmSans
        }
    }

    /** Resolves one request. Never throws; falls back to DM Sans. */
    suspend fun resolve(request: FontRequest): FontFamily {
        val name = request.name.trim()
        if (FontCatalog.isDefault(name)) return DmSans
        FontCatalog.systemFamily(name)?.let { return systemFamily(it, request) }
        return googleFamily(name, request)
    }

    private fun systemFamily(name: String, request: FontRequest): FontFamily {
        val style = when {
            request.bold && request.italic -> Typeface.BOLD_ITALIC
            request.bold -> Typeface.BOLD
            request.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return FontFamily(Typeface.create(name, style))
    }

    private suspend fun googleFamily(name: String, request: FontRequest): FontFamily {
        val weight = FontWeight(request.weight)
        val style = if (request.italic) FontStyle.Italic else FontStyle.Normal
        if (isProviderAvailable()) {
            // DM Sans follows as the fallback for the same weight and style.
            return FontFamily(
                listOf(Font(googleFont = GoogleFont(name, bestEffort = true), fontProvider = provider, weight = weight, style = style)) +
                    dmSansFallback(weight, style),
            )
        }
        val fetched = css.fetch(name, request.weight, request.italic) ?: return DmSans
        return FontFamily(
            listOf(Font(fetched.file, weight = weight, style = style)) + dmSansFallback(weight, style),
        )
    }

    private fun dmSansFallback(weight: FontWeight, style: FontStyle): List<Font> =
        listOf(Font(R.font.dm_sans, weight = weight, style = FontStyle.Normal)).takeIf { style == FontStyle.Normal }
            ?: listOf(Font(R.font.dm_sans_italic, weight = weight, style = FontStyle.Italic))

    private suspend fun isProviderAvailable(): Boolean {
        providerAvailable?.let { return it }
        val available = withContext(Dispatchers.IO) {
            runCatching { provider.isAvailableOnDevice(context) }.getOrDefault(false)
        }
        providerAvailable = available
        return available
    }

    /**
     * The Google Fonts family names for *Choose from list*, read from the bundled list in the
     * background; empty until then.
     */
    val googleFamilies: StateFlow<List<String>> by lazy {
        flow { emit(FontCatalog.loadGoogleFamilies(context)) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())
    }

    /** The *Choose from list* entries. [defaultLabel] is the localised "Default". */
    suspend fun catalog(defaultLabel: String = context.getString(R.string.backup_font_default)): List<FontEntry> =
        FontCatalog.entries(FontCatalog.loadGoogleFamilies(context), defaultLabel)
}
