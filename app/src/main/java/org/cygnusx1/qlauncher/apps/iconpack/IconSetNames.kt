/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.apps.iconpack

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.util.Locale

/**
 * Where icon sets come from: a GitHub repository (`owner/repo`) and the folder in it that holds
 * one folder per style ([stylesPath], `icons/styles`; empty for the repository's top folder).
 */
data class IconSetSource(val repo: String, val stylesPath: String)

/**
 * Names in an icon set: the GitHub repository, the archive entries that are icons, and the
 * display names of icon files. Plain Kotlin, so it is tested on the JVM.
 *
 * An icon set is a GitHub repository with one folder per style under a folder such as
 * `icons/styles/`, each holding PNG files named after the app they are for
 * (`icons/styles/glossy-cartoon/Netflix-Glossy.png`).
 */
object IconSetNames {
    /** The source offered before any is added. */
    val DEFAULT_SOURCE = IconSetSource("DrZacharySmith/projectivy-transparent-icons", "icons/styles")

    private val OWNER = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")
    private val REPO = Regex("[A-Za-z0-9._-]{1,100}")

    /**
     * `owner/repo` from what the user typed: `owner/repo`, or a github.com URL of the repository
     * or of a page inside it. Null when it is neither.
     */
    fun parseRepo(input: String): String? {
        var s = input.trim()
        s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        s = s.removePrefix("github.com/")
        val parts = s.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val owner = parts[0]
        val repo = parts[1].removeSuffix(".git")
        if (!OWNER.matches(owner) || !REPO.matches(repo) || repo == "." || repo == "..") return null
        return "$owner/$repo"
    }

    /**
     * A folder path inside a repository as typed (`icons/styles`, `/icons/styles/`), or null
     * when it is not one. Empty means the repository's top folder.
     */
    fun parsePath(input: String): String? {
        val parts = input.trim().split('/').filter { it.isNotBlank() }.map { it.trim() }
        if (parts.any { !safeName(it) }) return null
        return parts.joinToString("/")
    }

    /**
     * The style folder and file name of an archive entry that is an icon, or null. GitHub's
     * archives put everything under one top folder (`repo-tag/`), which is skipped; then come
     * [stylesPath], the style folder and the file. Only PNG files directly inside a style folder
     * count, and no name may climb out of its folder.
     */
    fun styleEntry(entryName: String, stylesPath: String): Pair<String, String>? {
        val path = stylesPath.split('/').filter { it.isNotEmpty() }
        val parts = entryName.split('/')
        if (parts.size != path.size + 3 || parts.subList(1, 1 + path.size) != path) return null
        val style = parts[path.size + 1]
        val file = parts[path.size + 2]
        if (!safeName(style) || !safeName(file) || !file.endsWith(".png", ignoreCase = true)) return null
        return style to file
    }

    /** The added sources as stored: one per line, `owner/repo`, a tab, then the path. */
    fun encodeSources(sources: List<IconSetSource>): String =
        sources.joinToString("\n") { "${it.repo}\t${it.stylesPath}" }

    /** The added sources from [encodeSources]; lines that are not a source are dropped. */
    fun decodeSources(stored: String): List<IconSetSource> = stored.lines().mapNotNull { line ->
        val repo = parseRepo(line.substringBefore('\t')) ?: return@mapNotNull null
        val path = parsePath(line.substringAfter('\t', "")) ?: return@mapNotNull null
        IconSetSource(repo, path)
    }.distinctBy { it.repo.lowercase(Locale.ROOT) }

    /**
     * The sources offered: the default (with the path it was added with, if it was), then the
     * added ones, then [selected] (with the default path) if it is none of them, as it was typed
     * before sources could be added.
     */
    fun sources(stored: String, selected: String): List<IconSetSource> {
        val added = decodeSources(stored)
        val isDefault = { src: IconSetSource -> src.repo.equals(DEFAULT_SOURCE.repo, ignoreCase = true) }
        val all = listOf(added.firstOrNull(isDefault) ?: DEFAULT_SOURCE) + added.filterNot(isDefault)
        val sel = parseRepo(selected)
        return if (sel == null || all.any { it.repo.equals(sel, ignoreCase = true) }) all else all + IconSetSource(sel, DEFAULT_SOURCE.stylesPath)
    }

    private fun safeName(s: String): Boolean =
        s.isNotBlank() && s != "." && s != ".." && !s.startsWith('.') && '\\' !in s && s.none { it.code < 0x20 }

    private val SIZE_SUFFIX = Regex("""\s*-?\s*16x9$""", RegexOption.IGNORE_CASE)

    /**
     * The display name of each icon file of one style: the file name without `.png`, without the
     * `-Suffix` most files of the style share (`-Glossy`, `-Metal`) and without a `16x9` note.
     */
    fun displayNames(fileNames: List<String>): Map<String, String> {
        val stems = fileNames.associateWith { it.substringBeforeLast('.') }
        val suffix = stems.values
            .mapNotNull { stem -> stem.substringAfterLast('-', "").trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() && '-' in stem } }
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }
            ?.takeIf { it.value * 2 > fileNames.size }
            ?.key
        return stems.mapValues { (_, stem) ->
            var name = stem
            if (suffix != null && name.substringAfterLast('-').trim().equals(suffix, ignoreCase = true)) {
                name = name.substringBeforeLast('-')
            }
            name = name.replace(SIZE_SUFFIX, "").trim()
            name.ifEmpty { stem }
        }
    }

    /** `glossy-cartoon` → `Glossy cartoon`. */
    fun styleLabel(folder: String): String =
        folder.replace('-', ' ').replace('_', ' ').trim().replaceFirstChar { it.titlecase(Locale.ROOT) }

    /** True for an alternative version of an icon (`Netflix (alt)`). */
    fun isAlt(name: String): Boolean = ALT.containsMatchIn(name)

    internal val ALT = Regex("""\((?:alt(?:ernative)?|\d+)\)""", RegexOption.IGNORE_CASE)
}

/**
 * Matches icon names to app titles: "Disney+" finds "Disney Plus", "Prime Video" finds "Prime",
 * "YouTube for Android TV" finds "YouTube", "Vimu Player" finds "Vimu". A score of 1 is the same name; [THRESHOLD] is the
 * lowest score worth proposing.
 */
object IconMatcher {
    const val THRESHOLD = 0.8

    /**
     * A name prepared once for many comparisons: the whole name, and the shorter forms it may
     * also go by (without a generic last word, or before a ":" or " - ").
     */
    class Key internal constructor(val full: String, val shorter: List<String>, val alt: Boolean)

    private val COMBINING = Regex("""\p{Mn}+""")
    /** A last word that says little about which app it is ("Vimu Player", "Prime Video", "YouTube for Android TV"). */
    private val GENERIC_TAIL = Regex("""\s*(?:for\s+)?(?:android\s+tv|google\s+tv|tv|player|app|video)$""", RegexOption.IGNORE_CASE)

    /** Where a label's tagline starts ("Spotify - Music and Podcasts", "Twitch: Live Streaming"). */
    private val TAGLINE = Regex("""\s*(?::|\s[-–|]\s).*$""")

    /** Lower case, accents dropped, `+` and `&` spelt out, and nothing but letters and digits. */
    fun normalize(s: String): String {
        val plain = Normalizer.normalize(s, Normalizer.Form.NFD).replace(COMBINING, "")
        return plain.lowercase(Locale.ROOT)
            .replace("+", "plus")
            .replace("&", "and")
            .filter { it.isLetterOrDigit() }
    }

    fun key(name: String): Key {
        val plain = name.replace(IconSetNames.ALT, " ").trim()
        val full = normalize(plain)
        val head = plain.replace(TAGLINE, "")
        val shorter = listOf(head, plain.replace(GENERIC_TAIL, ""), head.replace(GENERIC_TAIL, ""))
            .map(::normalize).filter { it.isNotEmpty() && it != full }.distinct()
        return Key(full, shorter, IconSetNames.isAlt(name))
    }

    /** How well an icon called [icon] fits an app called [title], from 0 to 1. */
    fun score(title: String, icon: String): Double = score(key(title), key(icon))

    fun score(title: Key, icon: Key): Double {
        if (title.full.isEmpty() || icon.full.isEmpty()) return 0.0
        // A shorter form on either side scores a little lower, so "YouTube" prefers the
        // "YouTube" icon to "YouTube TV".
        val titles = listOf(title.full) + title.shorter
        val icons = listOf(icon.full) + icon.shorter
        var best = 0.0
        for (i in titles.indices) {
            for (j in icons.indices) {
                val sim = similarity(titles[i], icons[j])
                best = maxOf(best, if (i == 0 && j == 0) sim else sim - 0.02)
            }
        }
        // The plain version wins over its alternatives.
        return if (icon.alt) best - 0.01 else best
    }

    /** The best of [icons] for an app known by any of [titles], at or above [THRESHOLD], or null. */
    fun <T> best(titles: List<String>, icons: List<Pair<T, Key>>): T? {
        val keys = titles.filter { it.isNotBlank() }.distinct().map(::key)
        return icons.map { (icon, k) -> icon to keys.maxOfOrNull { score(it, k) }.orZero() }
            .filter { it.second >= THRESHOLD }
            .maxByOrNull { it.second }
            ?.first
    }

    /** [icons] with those that fit [title] first, best first; the rest keep their order. */
    fun <T> rank(title: String, icons: List<T>, name: (T) -> String): List<T> {
        val t = key(title)
        val scored = icons.map { it to score(t, key(name(it))) }
        val good = scored.filter { it.second >= THRESHOLD }.sortedByDescending { it.second }.map { it.first }
        return good + scored.filter { it.second < THRESHOLD }.map { it.first }
    }

    private fun Double?.orZero(): Double = this ?: 0.0

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val (short, long) = if (a.length <= b.length) a to b else b to a
        // "prime" in "primevideo": a name that starts or ends the other, if it is not too short.
        val contained = if (short.length >= 4 && (long.startsWith(short) || long.endsWith(short))) {
            0.6 + 0.4 * short.length / long.length
        } else {
            0.0
        }
        // Spelling differences count only when they are few ("Acorntv" / "Acorn TV").
        val edit = (1.0 - levenshtein(a, b).toDouble() / long.length).takeIf { it >= 0.85 } ?: 0.0
        return maxOf(contained, edit)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }
}

/** A release of an icon-set repository, as GitHub's API lists it. */
data class IconSetRelease(val tag: String, val publishedAt: String?, val zipUrl: String)

/** Reads GitHub's `GET /repos/{owner}/{repo}/releases` answer. */
object IconSetFeed {
    @Serializable
    private data class GhRelease(
        @SerialName("tag_name") val tagName: String,
        @SerialName("published_at") val publishedAt: String? = null,
        @SerialName("zipball_url") val zipballUrl: String? = null,
        val draft: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** The published releases, newest first as GitHub sends them, at most [limit]. */
    fun parseReleases(repo: String, body: String, limit: Int): List<IconSetRelease> =
        json.decodeFromString(ListSerializer(GhRelease.serializer()), body)
            .filter { !it.draft && it.tagName.isNotBlank() }
            .take(limit)
            .map { r ->
                IconSetRelease(
                    tag = r.tagName,
                    publishedAt = r.publishedAt,
                    zipUrl = r.zipballUrl ?: "https://api.github.com/repos/$repo/zipball/${r.tagName}",
                )
            }
}
