/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.shared

import android.content.Intent
import kotlinx.coroutines.flow.StateFlow

/*
 * Models and interfaces shared between subsystems, so each can be built against the others'
 * contracts: channels publish programs, the notification listener publishes notifications and
 * Now Playing, the wallpaper engine and the home screen consume both.
 */

/** One program from the TV provider (a preview program or a Watch Next entry). */
data class Program(
    val id: Long,
    val packageName: String,
    val channelId: Long,
    val title: String,
    val episodeTitle: String? = null,
    val description: String? = null,
    val seasonNumber: String? = null,
    val episodeNumber: String? = null,
    val durationMillis: Long = 0L,
    val lastPlaybackPositionMillis: Long = 0L,
    val posterArtUri: String? = null,
    /** TvContract aspect ratio constant, -1 when unset. */
    val posterArtAspectRatio: Int = -1,
    val thumbnailUri: String? = null,
    val thumbnailAspectRatio: Int = -1,
    val logoUri: String? = null,
    val previewVideoUri: String? = null,
    val intentUri: String? = null,
    val weight: Int = 0,
    val isWatchNext: Boolean = false,
    val lastEngagementTimeMillis: Long = 0L,
)

/** A channel an app publishes. */
data class Channel(
    val id: Long,
    val packageName: String,
    val displayName: String,
    val browsable: Boolean,
    val logoUri: String? = null,
) {
    /** Package name plus display name, Projectivy's channel key. */
    val uniqueId: String get() = packageName + displayName
}

/** Read access to the TV provider. Implemented by the channels subsystem. */
interface ProgramSource {
    /** Whether READ_TV_LISTINGS is granted and the provider is readable. */
    val available: StateFlow<Boolean>
    val channels: StateFlow<List<Channel>>

    /** Programs of a channel, at most *Programs per channel*. */
    fun programs(channelId: Long): StateFlow<List<Program>>
    val watchNext: StateFlow<List<Program>>

    /** Every preview program across enabled channels (Random program wallpaper). */
    suspend fun allPreviewPrograms(): List<Program>
    suspend fun refresh()
}

/** The active media session. */
data class NowPlaying(
    val packageName: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    /** Album art, written to the cache dir; null when the session has none. */
    val artUri: String?,
    val isPlaying: Boolean,
    val canPlayPause: Boolean,
    val canStop: Boolean,
    val canSkipPrevious: Boolean,
    val canSkipNext: Boolean,
)

/** One notification as the notifications panel shows it. */
data class NotificationItem(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String?,
    val text: String?,
    val postTime: Long,
    val iconUri: String? = null,
    val contentIntent: Intent? = null,
    val clearable: Boolean = true,
)

/** Implemented by the notification listener. */
interface NotificationSource {
    /** True while QLauncher holds notification-listener access. */
    val accessGranted: StateFlow<Boolean>
    val notifications: StateFlow<List<NotificationItem>>
    val nowPlaying: StateFlow<NowPlaying?>
    fun open(item: NotificationItem)
    fun dismiss(item: NotificationItem)
    fun clearAll()
    fun mediaAction(action: MediaAction)
}

enum class MediaAction { PlayPause, Stop, Previous, Next }
