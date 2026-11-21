/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.system

import org.cygnusx1.qlauncher.system.notifications.NotificationFacts
import org.cygnusx1.qlauncher.system.notifications.NotificationFilter
import org.cygnusx1.qlauncher.system.notifications.NotificationFilter.FLAG_FOREGROUND_SERVICE
import org.cygnusx1.qlauncher.system.notifications.NotificationFilter.FLAG_GROUP_SUMMARY
import org.cygnusx1.qlauncher.system.notifications.NotificationFilter.FLAG_ONGOING_EVENT
import org.cygnusx1.qlauncher.system.notifications.NotificationRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationFilterTest {
    private val own = "org.cygnusx1.qlauncher"

    private fun facts(
        pkg: String = "com.example.mail",
        tag: String? = null,
        flags: Int = 0,
        title: String? = "Title",
        text: String? = "Text",
        badge: Boolean = true,
        media: Boolean = false,
        system: Boolean = false,
    ) = NotificationFacts(pkg, tag, flags, title, text, badge, media, system)

    private fun route(f: NotificationFacts) = NotificationFilter.route(f, own)

    @Test
    fun ordinaryNotificationIsListed() {
        assertEquals(NotificationRoute.Panel, route(facts()))
    }

    @Test
    fun foregroundServiceIsHidden() {
        assertEquals(NotificationRoute.Hidden, route(facts(flags = FLAG_FOREGROUND_SERVICE or FLAG_ONGOING_EVENT)))
    }

    @Test
    fun mediaIsKeptForNowPlayingEvenAsAForegroundService() {
        assertEquals(NotificationRoute.NowPlaying, route(facts(flags = FLAG_FOREGROUND_SERVICE or FLAG_ONGOING_EVENT, media = true)))
        assertEquals(NotificationRoute.NowPlaying, route(facts(tag = NotificationFilter.NOW_PLAYING_TAG)))
    }

    @Test
    fun ongoingSystemNotificationIsHiddenButOngoingAppOneIsListed() {
        assertEquals(NotificationRoute.Hidden, route(facts(pkg = "android", flags = FLAG_ONGOING_EVENT, system = true)))
        assertEquals(NotificationRoute.Panel, route(facts(flags = FLAG_ONGOING_EVENT)))
    }

    @Test
    fun groupSummaryWithoutTextIsHidden() {
        assertEquals(NotificationRoute.Hidden, route(facts(flags = FLAG_GROUP_SUMMARY, title = null, text = " ")))
        assertEquals(NotificationRoute.Panel, route(facts(flags = FLAG_GROUP_SUMMARY, title = "3 new messages", text = null)))
    }

    @Test
    fun appsThatMayNotShowABadgeAreHiddenExceptSystemUiAndUs() {
        assertEquals(NotificationRoute.Hidden, route(facts(badge = false)))
        assertEquals(NotificationRoute.Panel, route(facts(pkg = NotificationFilter.SYSTEM_UI_PACKAGE, badge = false, system = true)))
        assertEquals(NotificationRoute.Panel, route(facts(pkg = own, badge = false)))
    }

    @Test
    fun systemServerTagsAreHidden() {
        assertEquals(NotificationRoute.Hidden, route(facts(tag = "com.android.server.wm.AlertWindowNotification - x")))
        assertEquals(NotificationRoute.Hidden, route(facts(tag = "com.android.server.something", media = true)))
    }
}
