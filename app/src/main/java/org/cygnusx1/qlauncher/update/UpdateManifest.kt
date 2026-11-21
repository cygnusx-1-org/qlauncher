/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** A newer release: its version, what changed since the installed one, and where to get it. */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val changelog: List<String>,
    val downloadUrls: List<String>,
)

sealed interface ManifestResult {
    /** The installed version is the latest (or newer: a development build). */
    data class UpToDate(val latest: String) : ManifestResult

    data class Available(val update: UpdateInfo) : ManifestResult
}

/**
 * The update feed, in the format Electric Eel's update checker reads (`updates/qlauncher.json` in
 * the repository): a `package` object with the download URLs, and one object per version name with
 * its `versionCode` and `changelog`.
 *
 * ```
 * {
 *   "package": {
 *     "downloadUrlList_arm64-v8a": ["https://…/QLauncher_0.2.0_arm64-v8a.apk"],
 *     "downloadUrlList": ["https://…/QLauncher_0.2.0.apk"]
 *   },
 *   "0.2.0": { "versionCode": 2, "changelog": ["…"], "changelog_de": ["…"] },
 *   "0.1.0": { "versionCode": 1, "changelog": ["First release"] }
 * }
 * ```
 *
 * Versions are compared by name, as dot-separated numbers; the download list for the device's
 * primary ABI wins over the generic `downloadUrlList`, which wins over a single `downloadUrl`.
 */
object UpdateManifest {
    private val json = Json { ignoreUnknownKeys = true }

    /** @throws IllegalArgumentException when [text] is not a usable feed. */
    fun parse(text: String, currentVersion: String, abi: String?, language: String?): ManifestResult {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw IllegalArgumentException("The update feed is not a JSON object")
        val pkg = root["package"] as? JsonObject ?: throw IllegalArgumentException("No package in the update feed")
        val versions = root.filterKeys { it != "package" }.mapNotNull { (name, info) -> (info as? JsonObject)?.let { name to it } }
            .sortedWith { a, b -> compareVersions(b.first, a.first) }
        require(versions.isNotEmpty()) { "No versions in the update feed" }
        val (latest, latestInfo) = versions.first()
        if (compareVersions(currentVersion, latest) >= 0) return ManifestResult.UpToDate(latest)

        val urls = strings(pkg["downloadUrlList_$abi"]).ifEmpty { strings(pkg["downloadUrlList"]) }
            .ifEmpty { listOfNotNull((pkg["downloadUrl"] as? JsonPrimitive)?.takeIf { it.isString }?.content) }
        require(urls.isNotEmpty()) { "No download URL in the update feed" }

        // Everything newer than the installed version, newest first.
        val changelog = versions.filter { compareVersions(it.first, currentVersion) > 0 }.flatMap { (_, info) ->
            strings(language?.let { info["changelog_$it"] }).ifEmpty { strings(info["changelog"]) }
        }
        val code = (latestInfo["versionCode"] as? JsonPrimitive)?.intOrNull ?: 0
        return ManifestResult.Available(UpdateInfo(latest, code, changelog, urls))
    }

    private fun strings(element: Any?): List<String> =
        (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()

    /**
     * Compares versions like 0.12.3 part by part as numbers: 0.12.10 is newer than 0.12.9, and
     * 0.12 equals 0.12.0. A part's leading digits count (3-beta is 3); a part without any is 0.
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.trim().split('.')
        val pb = b.trim().split('.')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = leadingNumber(pa.getOrNull(i))
            val y = leadingNumber(pb.getOrNull(i))
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun leadingNumber(part: String?): Long =
        part?.takeWhile { it.isDigit() }?.take(18)?.toLongOrNull() ?: 0L
}
