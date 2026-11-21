/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.reddit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.data.AppJson
import org.cygnusx1.qlauncher.settings.WallpaperProviders
import org.cygnusx1.qlauncher.wallpaper.UpdateMode
import org.cygnusx1.qlauncher.wallpaper.Wallpaper
import org.cygnusx1.qlauncher.wallpaper.WallpaperEvent
import org.cygnusx1.qlauncher.wallpaper.source.ProviderException
import org.cygnusx1.qlauncher.wallpaper.source.WallpaperProvider
import java.io.File
import java.io.IOException

/** The acceptance settings the listing filter needs. */
data class RedditFilter(val minAcceptedWidth: Int, val skipWrongFormat: Boolean)

/**
 * The last good list per subreddit set, kept on disk so the wallpaper survives a restart offline.
 * Cleared on log out.
 */
class RedditListCache(private val file: File) {
    @Serializable
    private class Stored(val subs: String, val items: List<Wallpaper>)

    suspend fun load(subs: String): List<Wallpaper>? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        runCatching { AppJson.decodeFromString(Stored.serializer(), file.readText()) }.getOrNull()
            ?.takeIf { it.subs.equals(subs, ignoreCase = true) }?.items?.ifEmpty { null }
    }

    suspend fun save(subs: String, items: List<Wallpaper>) = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(AppJson.encodeToString(Stored.serializer(), Stored(subs, items)))
            tmp.renameTo(file)
        }
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) { file.delete(); Unit }
}

/**
 * *Reddit*: top posts of the month of the chosen subreddits, hot posts when that yields nothing,
 * read as the logged-in user. Logged out, it serves the last cached list if any (the engine then
 * shows the default wallpaper when there is none) and the *Configure* page says a login is needed.
 */
class RedditProvider(
    private val api: RedditApi,
    private val loggedIn: StateFlow<String?>,
    private val cache: RedditListCache,
    private val filter: () -> RedditFilter,
) : WallpaperProvider {
    override val id: String = WallpaperProviders.REDDIT
    override val updateMode: Int = UpdateMode.TIME_ELAPSED
    override val cacheMillis: Long = 60 * 60_000L

    override suspend fun getWallpapers(event: WallpaperEvent, params: String): List<Wallpaper> {
        val subs = RedditPresets.subredditsOf(params)
        if (subs.isEmpty()) throw ProviderException(R.string.wallpaper_error_no_subreddit)
        if (loggedIn.value == null) {
            return cache.load(subs) ?: throw ProviderException(R.string.wallpaper_reddit_login_needed)
        }
        return try {
            val f = filter()
            var items = fetch(subs, "top", "month", f)
            if (items.isEmpty()) items = fetch(subs, "hot", null, f)
            if (items.isNotEmpty()) cache.save(subs, items)
            items.ifEmpty { cache.load(subs).orEmpty() }
        } catch (e: RedditException.NotLoggedIn) {
            cache.load(subs) ?: throw ProviderException(R.string.wallpaper_reddit_login_needed, e)
        } catch (e: RedditException.RateLimited) {
            cache.load(subs) ?: throw ProviderException(R.string.wallpaper_error_rate_limited, e)
        } catch (e: RedditException.Http) {
            cache.load(subs) ?: throw ProviderException(if (e.code == 404) R.string.wallpaper_error_no_subreddit else R.string.wallpaper_error_server, e)
        } catch (e: IOException) {
            cache.load(subs) ?: throw ProviderException(R.string.wallpaper_error_network, e)
        }
    }

    private suspend fun fetch(subs: String, sort: String, time: String?, f: RedditFilter): List<Wallpaper> {
        val body = api.listing(subs, sort, time)
        val listing = RedditListingParser.parse(body, f.minAcceptedWidth, f.skipWrongFormat)
            ?: throw IOException("not a listing")
        return listing.wallpapers
    }
}
