/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.system.notifications

/** The facts about one notification that decide where it goes. */
data class NotificationFacts(
    val packageName: String,
    val tag: String?,
    /** `Notification.flags`. */
    val flags: Int,
    val title: CharSequence?,
    val text: CharSequence?,
    /** `Ranking.canShowBadge()`: the app (or the channel) may show a badge. */
    val canShowBadge: Boolean,
    /** A media notification: it carries a media session, is a transport, or is a TV "now playing" card. */
    val isMedia: Boolean,
    /** Posted by a system app (or the framework itself). */
    val isSystemApp: Boolean,
)

/** Where a notification goes. */
enum class NotificationRoute {
    /** Listed in the notifications panel and counted on the status bar. */
    Panel,

    /** Not listed; feeds the Now Playing card when no media session says more. */
    NowPlaying,

    /** Not shown at all. */
    Hidden,
}

/**
 * Which notifications the panel shows (matching what Projectivy's panel leaves
 * out): foreground-service and ongoing system notifications go (media ones are kept for Now
 * Playing), as do group summaries with no text, notifications from apps that may not show a badge
 * (except the system UI's and QLauncher's own), and anything tagged by the system server.
 */
object NotificationFilter {
    const val FLAG_ONGOING_EVENT = 0x2
    const val FLAG_FOREGROUND_SERVICE = 0x40
    const val FLAG_GROUP_SUMMARY = 0x200

    const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    const val SYSTEM_SERVER_TAG_PREFIX = "com.android.server"

    /** The tag Android TV's "now playing" notifications carry. */
    const val NOW_PLAYING_TAG = "Notification.NowPlaying"

    fun route(f: NotificationFacts, ownPackage: String): NotificationRoute {
        if (f.tag?.startsWith(SYSTEM_SERVER_TAG_PREFIX) == true) return NotificationRoute.Hidden
        if (f.isMedia || f.tag == NOW_PLAYING_TAG) return NotificationRoute.NowPlaying
        if (!f.canShowBadge && f.packageName != SYSTEM_UI_PACKAGE && f.packageName != ownPackage) return NotificationRoute.Hidden
        if (f.flags and FLAG_FOREGROUND_SERVICE != 0) return NotificationRoute.Hidden
        if (f.flags and FLAG_ONGOING_EVENT != 0 && f.isSystemApp) return NotificationRoute.Hidden
        if (f.flags and FLAG_GROUP_SUMMARY != 0 && f.title.isNullOrBlank() && f.text.isNullOrBlank()) return NotificationRoute.Hidden
        return NotificationRoute.Panel
    }
}
