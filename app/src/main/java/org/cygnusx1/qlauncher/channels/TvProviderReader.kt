/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.channels

import android.content.ContentResolver
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import org.cygnusx1.qlauncher.reports.ErrorReports
import org.cygnusx1.qlauncher.shared.Program

/**
 * Reads the TV provider (`content://android.media.tv`). Every call returns null when the
 * provider cannot be read (no permission, no provider), never throws.
 */
internal class TvProviderReader(private val resolver: ContentResolver) {

    fun channels(): List<ChannelRow>? = query(CHANNELS, CHANNEL_COLUMNS, null) { c ->
        val id = c.long("_id") ?: return@query null
        ChannelRow(
            id = id,
            packageName = c.string("package_name"),
            type = c.string("type"),
            displayName = c.string("display_name"),
            internalProviderId = c.string("internal_provider_id"),
            browsable = (c.int("browsable") ?: 1) == 1,
            logoUri = TvContract.buildChannelLogoUri(id).toString(),
        )
    }

    /** Every browsable preview program, highest weight first. */
    fun previewPrograms(): List<Program>? =
        query(PREVIEW_PROGRAMS, PREVIEW_COLUMNS, "weight DESC") { c -> c.program(watchNext = false) }

    /** Watch Next, most recent engagement first. */
    fun watchNext(): List<Program>? =
        query(WATCH_NEXT, WATCH_NEXT_COLUMNS, "last_engagement_time_utc_millis DESC") { c -> c.program(watchNext = true) }

    private fun Cursor.program(watchNext: Boolean): Program? {
        if ((int("browsable") ?: 1) != 1) return null
        val id = long("_id") ?: return null
        return Program(
            id = id,
            packageName = string("package_name").orEmpty(),
            channelId = if (watchNext) -1L else long("channel_id") ?: -1L,
            title = string("title").orEmpty(),
            episodeTitle = string("episode_title"),
            description = string("short_description"),
            seasonNumber = string("season_display_number"),
            episodeNumber = string("episode_display_number"),
            durationMillis = long("duration_millis") ?: 0L,
            lastPlaybackPositionMillis = long("last_playback_position_millis") ?: 0L,
            posterArtUri = string("poster_art_uri"),
            posterArtAspectRatio = int("poster_art_aspect_ratio") ?: -1,
            thumbnailUri = string("thumbnail_uri"),
            thumbnailAspectRatio = int("poster_thumbnail_aspect_ratio") ?: -1,
            logoUri = string("logo_uri"),
            previewVideoUri = string("preview_video_uri"),
            intentUri = string("intent_uri"),
            weight = int("weight") ?: 0,
            isWatchNext = watchNext,
            lastEngagementTimeMillis = if (watchNext) long("last_engagement_time_utc_millis") ?: 0L else 0L,
        )
    }

    private fun <T : Any> query(uri: Uri, projection: Array<String>, sort: String?, map: (Cursor) -> T?): List<T>? = try {
        resolver.query(uri, projection, null, null, sort)?.use { c ->
            val out = ArrayList<T>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) {
                try {
                    map(c)?.let(out::add)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Skipping a bad row of $uri", e)
                    ErrorReports.recordError("channels.row", e)
                }
            }
            out
        }
    } catch (e: SecurityException) {
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, "Cannot read $uri", e)
        ErrorReports.recordError("channels.read", e)
        null
    }

    private fun Cursor.index(name: String): Int = getColumnIndex(name)
    private fun Cursor.string(name: String): String? = index(name).let { if (it < 0 || isNull(it)) null else getString(it) }
    private fun Cursor.long(name: String): Long? = index(name).let { if (it < 0 || isNull(it)) null else getLong(it) }
    private fun Cursor.int(name: String): Int? = index(name).let { if (it < 0 || isNull(it)) null else getInt(it) }

    companion object {
        private const val TAG = "QLChannels"
        val CHANNELS: Uri = "content://android.media.tv/channel".toUri()
        val PREVIEW_PROGRAMS: Uri = "content://android.media.tv/preview_program".toUri()
        val WATCH_NEXT: Uri = "content://android.media.tv/watch_next_program".toUri()

        /** The provider root: observing it with descendants sees every table change. */
        val ROOT: Uri = "content://android.media.tv".toUri()

        private val CHANNEL_COLUMNS = arrayOf("_id", "package_name", "type", "display_name", "internal_provider_id", "browsable")

        private val PROGRAM_COLUMNS = listOf(
            "_id", "package_name", "title", "episode_title", "short_description", "season_display_number",
            "episode_display_number", "duration_millis", "last_playback_position_millis", "poster_art_uri",
            "poster_art_aspect_ratio", "thumbnail_uri", "poster_thumbnail_aspect_ratio", "logo_uri",
            "preview_video_uri", "intent_uri", "weight", "browsable",
        )
        private val PREVIEW_COLUMNS = (PROGRAM_COLUMNS + "channel_id").toTypedArray()
        private val WATCH_NEXT_COLUMNS = (PROGRAM_COLUMNS + "last_engagement_time_utc_millis").toTypedArray()
    }
}
