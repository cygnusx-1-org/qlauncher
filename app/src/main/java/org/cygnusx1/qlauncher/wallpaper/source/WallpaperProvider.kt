/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.source

import android.content.ComponentName
import androidx.annotation.StringRes
import org.cygnusx1.qlauncher.wallpaper.Wallpaper
import org.cygnusx1.qlauncher.wallpaper.WallpaperEvent

/**
 * A source of wallpapers. Built-in providers and plugins share this interface, as in Projectivy.
 *
 * @property updateMode the [UpdateMode][org.cygnusx1.qlauncher.wallpaper.UpdateMode] bits of the
 *   events it reacts to.
 * @property cacheMillis how long the engine keeps its list before asking again; 0 = ask on every
 *   event.
 */
interface WallpaperProvider {
    val id: String
    val updateMode: Int
    val cacheMillis: Long

    /**
     * The provider's current list for [event], given its stored parameter string [params]. Runs
     * off the main thread. Throws [ProviderException] for a failure the *Configure* page should
     * show; any other exception is shown as a generic error.
     */
    suspend fun getWallpapers(event: WallpaperEvent, params: String): List<Wallpaper>
}

/** A failure with a message for the *Configure* page. */
class ProviderException(@param:StringRes val messageRes: Int, cause: Throwable? = null) : Exception(null, cause)

/** One entry of the *Launcher wallpaper* dropdown. */
data class ProviderInfo(
    val id: String,
    val name: String,
    val isPlugin: Boolean = false,
    /** The plugin's package, stored in `Keys.Wallpaper.providerPackage`; empty for built-ins. */
    val packageName: String = "",
    /** The plugin's own settings screen, opened from *Configure*; null for built-ins. */
    val settingsActivity: ComponentName? = null,
) {
    /** The dropdown label: plugins get a plug marker before their name. */
    val label: String get() = if (isPlugin) "$PLUG_MARKER$name" else name

    companion object {
        const val PLUG_MARKER = "🔌 "
    }
}

/** What the *Configure* page shows about a provider. */
data class ProviderStatus(
    val loading: Boolean = false,
    /** Wallpapers in the provider's current list (*Show wallpapers* subtitle). */
    val count: Int = 0,
    /** The last failure, already a user-facing sentence; null when the last load worked. */
    val error: String? = null,
    /** True for Reddit while logged out. */
    val needsLogin: Boolean = false,
)
