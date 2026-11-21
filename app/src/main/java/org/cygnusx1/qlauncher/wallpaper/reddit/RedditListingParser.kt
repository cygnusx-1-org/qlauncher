/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.reddit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.cygnusx1.qlauncher.wallpaper.Wallpaper
import org.cygnusx1.qlauncher.wallpaper.WallpaperDisplayModes
import org.cygnusx1.qlauncher.wallpaper.WallpaperType
import org.cygnusx1.qlauncher.wallpaper.engine.Acceptance

/** One parsed listing page. */
data class RedditListing(val wallpapers: List<Wallpaper>, val after: String?)

/**
 * Picks wallpapers out of a subreddit listing:
 * - image posts (`post_hint == "image"`) and galleries (`is_gallery`, through `media_metadata`);
 * - URL and size from `preview.images[0].source`, else `url` on `i.redd.it`;
 * - no `over_18`, `spoiler`, removed or stickied posts;
 * - with the *Skip* policy, pictures that are the wrong format (portrait, or narrower than the
 *   minimum width) are dropped here, from the listing's sizes, before anything is downloaded.
 */
object RedditListingParser {

    /** Null when [json] is not a listing. */
    fun parse(json: String, minAcceptedWidth: Int, skipWrongFormat: Boolean): RedditListing? {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
        val data = root["data"] as? JsonObject ?: return null
        val children = data["children"] as? JsonArray ?: return null
        val out = mutableListOf<Wallpaper>()
        for (child in children) {
            val post = (child as? JsonObject)?.get("data") as? JsonObject ?: continue
            out += wallpapersOf(post)
        }
        val filtered = out
            .filter { !skipWrongFormat || !Acceptance.isWrongFormat(it.width, it.height, minAcceptedWidth) }
            .distinctBy { it.uri }
        return RedditListing(filtered, data.str("after"))
    }

    /** The wallpapers of one post; empty when it does not qualify. */
    fun wallpapersOf(post: JsonObject): List<Wallpaper> {
        if (post.bool("over_18") || post.bool("spoiler") || post.bool("stickied") || isRemoved(post)) return emptyList()
        if (post.bool("is_video")) return emptyList()
        val title = post.str("title")
        val author = post.str("author")
        val sub = post.str("subreddit")
        val permalink = post.str("permalink")
        val source = permalink?.let { "https://www.reddit.com$it" }
        val by = buildString {
            if (author != null) append("u/").append(author)
            if (sub != null) {
                if (isNotEmpty()) append(" · ")
                append("r/").append(sub)
            }
        }.ifEmpty { null }

        fun wallpaper(url: String, w: Int, h: Int) = Wallpaper(
            uri = url,
            type = WallpaperType.IMAGE,
            displayMode = WallpaperDisplayModes.CROP,
            title = title,
            source = source,
            author = by,
            width = w,
            height = h,
        )

        if (post.bool("is_gallery")) {
            val meta = post["media_metadata"] as? JsonObject ?: return emptyList()
            val order = ((post["gallery_data"] as? JsonObject)?.get("items") as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.str("media_id") }
                ?: meta.keys.toList()
            return order.mapNotNull { id ->
                val m = meta[id] as? JsonObject ?: return@mapNotNull null
                if (m.str("status") != "valid" || m.str("e") != "Image") return@mapNotNull null
                val s = m["s"] as? JsonObject ?: return@mapNotNull null
                val url = s.str("u")?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
                wallpaper(url, s.int("x") ?: 0, s.int("y") ?: 0)
            }
        }

        if (post.str("post_hint") != "image") return emptyList()
        val source0 = (((post["preview"] as? JsonObject)?.get("images") as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.get("source") as? JsonObject
        val previewUrl = source0?.str("url")?.takeIf { it.startsWith("https://") }
        if (previewUrl != null) return listOf(wallpaper(previewUrl, source0.int("width") ?: 0, source0.int("height") ?: 0))
        val url = post.str("url")?.takeIf { it.startsWith("https://i.redd.it/") } ?: return emptyList()
        return listOf(wallpaper(url, 0, 0))
    }

    private fun isRemoved(post: JsonObject): Boolean {
        val byCategory = post["removed_by_category"]
        if (byCategory != null && byCategory !is JsonNull) return true
        return post.bool("removed") || post.str("author") == "[deleted]" && post.str("selftext") == "[removed]"
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
}
