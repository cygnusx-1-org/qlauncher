/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.reddit

/** One entry of the *Subreddit* dropdown: a display name and the subreddit(s) it reads. */
data class RedditPreset(val label: String, val subreddit: String)

/**
 * The *Subreddit* presets, Projectivy's list minus its two branded entries.
 * The parameter stores the subreddit name, compared without regard to case, so Projectivy's
 * stored values (`EARTHPORN`) select the same preset. Any other value is a custom list of
 * subreddits joined with `+`.
 */
object RedditPresets {
    val entries: List<RedditPreset> = listOf(
        RedditPreset("Earth", "EarthPorn"),
        RedditPreset("TMDB Wallpapers", "tmdbwallpapers"),
        RedditPreset("ArtWp", "ArtWp"),
        RedditPreset("TvDark Wallpapers", "TVdarkWallpapers"),
        RedditPreset("Wallpaper AndroidTv", "wallpaperandroidtv"),
        RedditPreset("Astrophotography", "astrophotography"),
        RedditPreset("Wallpaper", "wallpaper"),
        RedditPreset("Wallpapers", "wallpapers"),
        RedditPreset("Big Wallpapers", "bigwallpapers"),
        RedditPreset("Botanical", "BotanicalPorn"),
        RedditPreset("Sea", "seaporn"),
        RedditPreset("Sky", "SkyPorn"),
        RedditPreset("Car", "carporn"),
        RedditPreset("Animal", "AnimalPorn"),
        RedditPreset("Dall·e & Sora", "dalle2"),
        RedditPreset("Midjourney", "midjourney"),
    )

    val DEFAULT: RedditPreset = entries.first()

    /** The preset a parameter selects; null for a custom value (the *[Custom subreddit]* entry). */
    fun presetOf(params: String): RedditPreset? = entries.firstOrNull { it.subreddit.equals(params.trim(), ignoreCase = true) }

    /**
     * The `+`-joined subreddits to read for a parameter: names keep letters, digits and `_`
     * (an optional `r/` prefix is dropped); empty when nothing usable is left.
     */
    fun subredditsOf(params: String): String = params
        .split('+', ',', ' ')
        .map { it.trim().removePrefix("/").removePrefix("r/").removePrefix("R/") }
        .map { s -> s.filter { it.isLetterOrDigit() && it.code < 128 || it == '_' } }
        .filter { it.length in 2..21 }
        .distinctBy { it.lowercase() }
        .joinToString("+")
}
