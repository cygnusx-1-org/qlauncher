/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.fonts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Fetches a Google Fonts family file through the public CSS API, for devices without the Play
 * services font provider: `css2?family=<Family+Name>:ital,wght@<0|1>,<weight>`, then the
 * `src: url(...)` it names. Files are cached in [cacheDir] and reused without the network.
 *
 * When the exact style is not published (HTTP 400), the regular style of the same weight, then
 * weight 400, are tried; Compose synthesises what is missing.
 */
class GoogleFontsCss(
    private val http: OkHttpClient,
    private val cacheDir: File,
    private val baseUrl: HttpUrl = "https://fonts.googleapis.com/css2".toHttpUrl(),
) {
    private val mutex = Mutex()

    /** A downloaded font file and the style it really has. */
    data class Fetched(val file: File, val weight: Int, val italic: Boolean)

    /** The CSS URL for one style. Spaces become `+`, as the API expects. */
    fun cssUrl(family: String, weight: Int, italic: Boolean): HttpUrl {
        val value = "${family.trim()}:ital,wght@${if (italic) 1 else 0},$weight"
        // HttpUrl would encode a space as %20; the API documents '+'. Build the query by hand.
        val encoded = value.split(' ').joinToString("+") { part ->
            java.net.URLEncoder.encode(part, "UTF-8")
        }.replace("%3A", ":").replace("%40", "@").replace("%2C", ",")
        return baseUrl.newBuilder().encodedQuery("family=$encoded").build()
    }

    /** The cache file for one style. */
    fun cacheFile(family: String, weight: Int, italic: Boolean): File {
        val safe = family.trim().replace(Regex("[^A-Za-z0-9]+"), "_")
        return File(cacheDir, "${safe}_${weight}_${if (italic) "i" else "n"}.ttf")
    }

    /**
     * The font file for [family] in the requested style, from the cache or the network; null
     * when the family does not exist or the network fails.
     */
    suspend fun fetch(family: String, weight: Int, italic: Boolean): Fetched? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val attempts = linkedSetOf(weight to italic, weight to false, 400 to italic, 400 to false)
            for ((w, i) in attempts) {
                val cached = cacheFile(family, w, i)
                if (cached.isFile && cached.length() > 0) return@withLock Fetched(cached, w, i)
            }
            for ((w, i) in attempts) {
                when (val r = download(family, w, i)) {
                    is Result.Ok -> return@withLock Fetched(r.file, w, i)
                    Result.NotPublished -> continue
                    Result.Failed -> return@withLock null
                }
            }
            null
        }
    }

    private sealed interface Result {
        data class Ok(val file: File) : Result
        data object NotPublished : Result
        data object Failed : Result
    }

    private fun download(family: String, weight: Int, italic: Boolean): Result {
        val css = try {
            http.newCall(Request.Builder().url(cssUrl(family, weight, italic)).build()).execute().use { resp ->
                when {
                    resp.code == 400 || resp.code == 404 -> return Result.NotPublished
                    !resp.isSuccessful -> return Result.Failed
                    else -> resp.body.string()
                }
            }
        } catch (_: IOException) {
            return Result.Failed
        }
        val url = fontUrl(css) ?: return Result.NotPublished
        val target = cacheFile(family, weight, italic)
        return try {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return Result.Failed
                target.parentFile?.mkdirs()
                val tmp = File(target.path + ".part")
                tmp.outputStream().use { out -> resp.body.byteStream().copyTo(out) }
                if (!looksLikeFont(tmp)) {
                    tmp.delete()
                    return Result.Failed
                }
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    return Result.Failed
                }
                Result.Ok(target)
            }
        } catch (_: IOException) {
            Result.Failed
        } catch (_: IllegalArgumentException) {
            Result.Failed
        }
    }

    companion object {
        private val SRC_URL = Regex("""src:\s*url\(\s*['"]?([^'")\s]+)['"]?\s*\)""")

        /** The first font URL in a Google Fonts CSS answer, or null. */
        fun fontUrl(css: String): String? = SRC_URL.find(css)?.groupValues?.get(1)

        /** TrueType (`00 01 00 00`, `true`) or OpenType (`OTTO`); WOFF2 cannot be loaded. */
        fun looksLikeFont(file: File): Boolean {
            val head = ByteArray(4)
            val n = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(-1)
            if (n < 4) return false
            val tag = String(head, Charsets.ISO_8859_1)
            return tag == "\u0000\u0001\u0000\u0000" || tag == "OTTO" || tag == "true" || tag == "ttcf"
        }
    }
}
