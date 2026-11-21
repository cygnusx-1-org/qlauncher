/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.install

import androidx.annotation.StringRes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.cygnusx1.qlauncher.R

/**
 * One app *Install 3rd party apps* offers. The shape is that of Projectivy's own list:
 * a name, a category, a package name, an optional download URL (the app's own release file)
 * and an optional Downloader code. An entry with only a package name installs from the store.
 */
@Serializable
data class CatalogueEntry(
    val name: String,
    val category: String = CatalogueCategory.TOOL.id,
    @SerialName("package") val packageName: String? = null,
    val url: String? = null,
    val downloader: String? = null,
) {
    val categoryKind: CatalogueCategory get() = CatalogueCategory.of(category)

    /** Install methods in the order the chooser lists them: store, direct download, Downloader. */
    val methods: List<InstallMethod>
        get() = buildList {
            if (!packageName.isNullOrBlank()) add(InstallMethod.Store)
            if (!url.isNullOrBlank()) add(InstallMethod.Direct)
            if (!downloader.isNullOrBlank()) add(InstallMethod.Downloader)
        }

    /** What a single press does: a direct download when there is one, else Downloader, else the store. */
    val defaultMethod: InstallMethod?
        get() = when {
            !url.isNullOrBlank() -> InstallMethod.Direct
            !downloader.isNullOrBlank() -> InstallMethod.Downloader
            !packageName.isNullOrBlank() -> InstallMethod.Store
            else -> null
        }
}

enum class InstallMethod(@param:StringRes @get:StringRes val label: Int) {
    Store(R.string.apps_install_method_store),
    Direct(R.string.apps_install_method_direct),
    Downloader(R.string.apps_install_method_downloader),
}

/** The four groups of the page, in page order. */
enum class CatalogueCategory(val id: String, @param:StringRes @get:StringRes val label: Int) {
    WALLPAPER_PLUGIN("Wallpaper Plugin", R.string.apps_install_category_wallpaper_plugin),
    ICON_PACK("Icon Pack", R.string.apps_install_category_icon_pack),
    FILE_MANAGER("File Manager", R.string.apps_install_category_file_manager),
    TOOL("Tool", R.string.apps_install_category_tool),
    ;

    companion object {
        /** Unknown or empty categories are tools, as in Projectivy. */
        fun of(id: String?): CatalogueCategory = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: TOOL
    }
}

@Serializable
private data class CatalogueFile(val installPackages: List<CatalogueEntry> = emptyList())

object Catalogue {
    /** The bundled asset. */
    const val ASSET = "apps_install_catalogue.json"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /**
     * Parses a catalogue: `{"installPackages": [...]}`. Names and fields are trimmed, blank
     * optional fields become null, entries without a name or without any install route are
     * dropped. Malformed JSON gives an empty list.
     */
    fun parse(text: String): List<CatalogueEntry> {
        val file = try {
            json.decodeFromString(CatalogueFile.serializer(), text)
        } catch (e: SerializationException) {
            return emptyList()
        } catch (e: IllegalArgumentException) {
            return emptyList()
        }
        return file.installPackages.mapNotNull { e ->
            val clean = CatalogueEntry(
                name = e.name.trim(),
                category = CatalogueCategory.of(e.category).id,
                packageName = e.packageName?.trim()?.ifEmpty { null },
                url = e.url?.trim()?.ifEmpty { null },
                downloader = e.downloader?.trim()?.ifEmpty { null },
            )
            clean.takeIf { it.name.isNotEmpty() && it.defaultMethod != null }
        }
    }

    /** Entries grouped by category in page order; empty groups left out. */
    fun grouped(entries: List<CatalogueEntry>): List<Pair<CatalogueCategory, List<CatalogueEntry>>> =
        CatalogueCategory.entries.mapNotNull { c ->
            entries.filter { it.categoryKind == c }.takeIf { it.isNotEmpty() }?.let { c to it }
        }
}
