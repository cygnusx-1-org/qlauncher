/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.source

import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.data.AppJson
import org.cygnusx1.qlauncher.settings.WallpaperProviders
import org.cygnusx1.qlauncher.wallpaper.UpdateMode
import org.cygnusx1.qlauncher.wallpaper.Wallpaper
import org.cygnusx1.qlauncher.wallpaper.WallpaperDisplayModes
import org.cygnusx1.qlauncher.wallpaper.WallpaperEvent
import org.cygnusx1.qlauncher.wallpaper.WallpaperType
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The Wikimedia Commons categories. The parameter stores the enum name, as Projectivy does
 * (`MOUNTAINS`); a custom search is stored as `q:<text>`.
 */
enum class WikimediaCategory(@param:StringRes val labelRes: Int, val query: String) {
    WALLPAPERS(R.string.wallpaper_wikimedia_wallpapers, "incategory:\"Commons featured desktop backgrounds\""),
    NATURE(R.string.wallpaper_wikimedia_nature, "deepcat:\"Featured pictures of nature\""),
    LANDSCAPES(R.string.wallpaper_wikimedia_landscapes, "incategory:\"Featured pictures of landscapes\""),
    MOUNTAINS(R.string.wallpaper_wikimedia_mountains, "incategory:\"Featured pictures of mountains\""),
    FORESTS(R.string.wallpaper_wikimedia_forests, "incategory:\"Featured pictures of forests\""),
    CITIES(R.string.wallpaper_wikimedia_cities, "incategory:\"Featured pictures of cityscapes\""),
    ARCHITECTURE(R.string.wallpaper_wikimedia_architecture, "deepcat:\"Featured pictures of architecture\""),
    NIGHT(R.string.wallpaper_wikimedia_night, "deepcat:\"Featured pictures of cityscapes\" night"),
    SPACE(R.string.wallpaper_wikimedia_space, "deepcat:\"Featured pictures of astronomy\""),
    ANIMALS(R.string.wallpaper_wikimedia_animals, "deepcat:\"Featured pictures of animals\""),

    /** *[Custom search]*: the text is in the parameter after [CUSTOM_PREFIX]. */
    CUSTOM(R.string.wallpaper_wikimedia_custom, ""),
    ;

    companion object {
        val DEFAULT = LANDSCAPES
        const val CUSTOM_PREFIX = "q:"
        private const val CUSTOM_SCOPE = "incategory:\"Featured pictures on Wikimedia Commons\" "

        /** The category a parameter selects; unknown names fall back to [DEFAULT]. */
        fun of(params: String): WikimediaCategory {
            if (params.startsWith(CUSTOM_PREFIX)) return CUSTOM
            return entries.firstOrNull { it != CUSTOM && it.name.equals(params, ignoreCase = true) } ?: DEFAULT
        }

        /** The custom search text of a `q:` parameter, empty otherwise. */
        fun customText(params: String): String = if (params.startsWith(CUSTOM_PREFIX)) params.removePrefix(CUSTOM_PREFIX) else ""

        fun customParam(text: String): String = CUSTOM_PREFIX + text

        /**
         * The search for a parameter; null for an empty custom search. Custom text loses its
         * quotes, is trimmed to 100 characters and is limited to featured pictures.
         */
        fun queryFor(params: String): String? {
            val category = of(params)
            if (category != CUSTOM) return category.query
            val text = customText(params).replace('"', ' ').trim().take(100).trim()
            return if (text.isEmpty()) null else CUSTOM_SCOPE + text
        }
    }
}

/** The Commons API request and its parsing, pure so it can be tested against fixtures. */
object WikimediaApi {
    const val ENDPOINT = "https://commons.wikimedia.org/w/api.php"
    val MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    private const val MIN_WIDTH = 1920
    private const val MIN_HEIGHT = 1080
    private const val MIN_RATIO = 1.3f
    private const val MAX_ORIGINAL_BYTES = 8L * 1024 * 1024
    private const val MAX_AUTHOR = 80
    private const val FALLBACK_AUTHOR = "Wikimedia Commons"

    /** The request Projectivy makes, for [query]. */
    fun url(query: String, base: HttpUrl = ENDPOINT.toHttpUrl()): HttpUrl = base.newBuilder()
        .addQueryParameter("action", "query")
        .addQueryParameter("format", "json")
        .addQueryParameter("formatversion", "2")
        .addQueryParameter("generator", "search")
        .addQueryParameter("gsrsearch", query)
        .addQueryParameter("gsrnamespace", "6")
        .addQueryParameter("gsrlimit", "50")
        .addQueryParameter("gsrsort", "random")
        .addQueryParameter("prop", "imageinfo")
        .addQueryParameter("iiprop", "url|size|mime|extmetadata")
        .addQueryParameter("iiurlwidth", "1920")
        .addQueryParameter("iiextmetadatalanguage", "en")
        .addQueryParameter("iiextmetadatafilter", "Artist|Attribution|LicenseShortName|LicenseUrl|UsageTerms")
        .build()

    /**
     * The wallpapers in an API reply. Keeps JPEG, PNG and WebP at least 1920 × 1080 with a ratio
     * of 1.3 or more and a licence; prefers the 1920-wide thumbnail, else the original when it is
     * at most 8 MB. Returns null when the reply is an API error or not JSON.
     */
    fun parse(body: String): List<Wallpaper>? {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        if (root["error"] != null) return null
        val pages = (root["query"] as? JsonObject)?.get("pages") as? JsonArray ?: return emptyList()
        return pages.mapNotNull { page -> (page as? JsonObject)?.let(::parsePage) }
    }

    private fun parsePage(page: JsonObject): Wallpaper? {
        val info = (page["imageinfo"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val width = info.int("width") ?: return null
        val height = info.int("height") ?: return null
        val mime = info.string("mime")
        if (width < MIN_WIDTH || height < MIN_HEIGHT || width.toFloat() / height < MIN_RATIO || mime !in MIME_TYPES) return null
        val thumb = info.string("thumburl")?.takeIf { it.startsWith("https://") }
        val original = info.string("url")?.takeIf { it.startsWith("https://") && (info.long("size") ?: 0L) <= MAX_ORIGINAL_BYTES }
        val url = thumb ?: original ?: return null
        val meta = info["extmetadata"] as? JsonObject
        val licence = meta.metaValue("LicenseShortName") ?: return null
        var author = meta.metaValue("Artist") ?: meta.metaValue("Attribution") ?: FALLBACK_AUTHOR
        if (author.length > MAX_AUTHOR) author = author.take(MAX_AUTHOR - 1) + "…"
        val title = page.string("title")?.let(::titleOf)
        val source = info.string("descriptionurl")?.takeIf { it.startsWith("https://") }
        val (w, h) = if (thumb != null) {
            val tw = info.int("thumbwidth") ?: 1920
            val th = info.int("thumbheight") ?: (tw.toLong() * height / width).toInt()
            tw to th
        } else {
            width to height
        }
        return Wallpaper(
            uri = url,
            type = WallpaperType.IMAGE,
            displayMode = WallpaperDisplayModes.CROP,
            title = title,
            source = source,
            author = author,
            licence = licence,
            width = w,
            height = h,
        )
    }

    /** `File:Some_picture.jpg` → `Some picture`. */
    fun titleOf(fileTitle: String): String? {
        var t = fileTitle.removePrefix("File:")
        val dot = t.lastIndexOf('.')
        if (dot != -1) t = t.substring(0, dot)
        return t.replace('_', ' ').trim().ifEmpty { null }
    }

    private fun JsonObject?.metaValue(name: String): String? {
        val v = ((this?.get(name) as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull ?: return null
        return stripHtml(v).ifBlank { null }
    }

    /** Removes tags and common entities and collapses white space. */
    fun stripHtml(html: String): String {
        val noTags = html.replace(Regex("<[^>]*>"), " ")
        val decoded = noTags
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
        return decoded.replace(Regex("\\s+"), " ").trim()
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}

/**
 * Wikimedia Commons: one search request per category, the list cached on disk for a day, with a
 * failure backoff of 1 minute doubling to 6 hours that honours `Retry-After` on 429 and 503.
 */
class WikimediaProvider(
    private val client: OkHttpClient,
    private val cacheDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val endpoint: HttpUrl = WikimediaApi.ENDPOINT.toHttpUrl(),
) : WallpaperProvider {
    override val id: String = WallpaperProviders.WIKIMEDIA
    override val updateMode: Int = UpdateMode.TIME_ELAPSED
    override val cacheMillis: Long = 60 * 60_000L

    private val backoff = Backoff(clock)
    private val mutex = Mutex()

    @Serializable
    private class CachedList(val query: String, val fetchedAt: Long, val items: List<Wallpaper>)

    override suspend fun getWallpapers(event: WallpaperEvent, params: String): List<Wallpaper> = mutex.withLock {
        val query = WikimediaCategory.queryFor(params) ?: return@withLock emptyList()
        val cached = readCache(query)
        if (cached != null && clock() - cached.fetchedAt < LIST_CACHE_MILLIS) return@withLock cached.items
        if (backoff.isBlocked()) {
            return@withLock cached?.items ?: throw ProviderException(R.string.wallpaper_error_rate_limited)
        }
        val request = Request.Builder().url(WikimediaApi.url(query, endpoint)).build()
        try {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    val hint = if (response.code == 429 || response.code == 503) Backoff.parseRetryAfter(response.header("Retry-After")) else null
                    backoff.fail(hint)
                    return@withLock cached?.items ?: throw ProviderException(
                        if (response.code == 429 || response.code == 503) R.string.wallpaper_error_rate_limited else R.string.wallpaper_error_server,
                    )
                }
                val body = withContext(Dispatchers.IO) { response.body.string() }
                val items = WikimediaApi.parse(body)
                if (items == null) {
                    backoff.fail()
                    return@withLock cached?.items ?: throw ProviderException(R.string.wallpaper_error_server)
                }
                backoff.reset()
                if (items.isNotEmpty()) writeCache(CachedList(query, clock(), items))
                items.ifEmpty { cached?.items.orEmpty() }
            }
        } catch (e: IOException) {
            backoff.fail()
            cached?.items ?: throw ProviderException(R.string.wallpaper_error_network, e)
        }
    }

    private fun cacheFile(query: String): File = File(cacheDir, "wikimedia-${sha1(query)}.json")

    private suspend fun readCache(query: String): CachedList? = withContext(Dispatchers.IO) {
        val f = cacheFile(query)
        if (!f.isFile) return@withContext null
        runCatching { AppJson.decodeFromString(CachedList.serializer(), f.readText()) }.getOrNull()?.takeIf { it.query == query }
    }

    private suspend fun writeCache(list: CachedList) = withContext(Dispatchers.IO) {
        runCatching {
            cacheDir.mkdirs()
            val f = cacheFile(list.query)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(AppJson.encodeToString(CachedList.serializer(), list))
            tmp.renameTo(f)
        }
    }

    companion object {
        /** The search results are kept for a day. */
        const val LIST_CACHE_MILLIS = 24 * 60 * 60_000L

        internal fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
