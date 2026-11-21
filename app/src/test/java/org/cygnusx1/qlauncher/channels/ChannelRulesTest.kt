/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.channels

import org.cygnusx1.qlauncher.channels.inputs.TvInputs
import org.cygnusx1.qlauncher.data.CategoryIds
import org.cygnusx1.qlauncher.data.CategoryList
import org.cygnusx1.qlauncher.data.CategoryType
import org.cygnusx1.qlauncher.data.DefaultCategories
import org.cygnusx1.qlauncher.shared.Channel
import org.cygnusx1.qlauncher.shared.Program
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EnableChannelsTest {
    @Test
    fun unsetFollowsThePermission() {
        assertTrue(ChannelRules.isEnabled(isSet = false, stored = false, permissionGranted = true))
        assertFalse(ChannelRules.isEnabled(isSet = false, stored = false, permissionGranted = false))
    }

    @Test
    fun aStoredValueWins() {
        assertFalse(ChannelRules.isEnabled(isSet = true, stored = false, permissionGranted = true))
        assertTrue(ChannelRules.isEnabled(isSet = true, stored = true, permissionGranted = false))
    }
}

class ChannelRulesTest {
    private fun row(id: Long, pkg: String?, type: String? = "TYPE_PREVIEW", name: String = "Ch$id", provider: String? = null) =
        ChannelRow(id, pkg, type, name, provider, browsable = true)

    private fun program(id: Long, channel: Long, pkg: String = "p", intent: String? = "intent:$id", engaged: Long = 0) =
        Program(id = id, packageName = pkg, channelId = channel, title = "T$id", intentUri = intent, lastEngagementTimeMillis = engaged)

    @Test
    fun onlyPreviewChannelsOfOrdinaryAppsAreKept() {
        val channels = ChannelRules.channels(
            listOf(
                row(1, "org.jellyfin"),
                row(2, "com.google.android.tvrecommendations"),
                row(3, "com.android.tv", type = "TYPE_OTHER"),
                row(4, null),
                row(5, "org.kodi", name = "Suggestions"),
            ),
        )
        assertEquals(listOf(1L, 5L), channels.map { it.id })
        assertEquals("org.kodiSuggestions", channels[1].uniqueId)
    }

    @Test
    fun aRepublishedChannelIsReadOnce() {
        val channels = ChannelRules.channels(
            listOf(
                row(1, "a", provider = "home"),
                row(2, "a", provider = "home"),
                row(3, "b", provider = "home"),
                row(4, "a", provider = null),
                row(5, "a", provider = null),
            ),
        )
        assertEquals(listOf(1L, 3L, 4L, 5L), channels.map { it.id })
    }

    @Test
    fun programsAreLimitedAndDeduplicatedPerChannel() {
        val programs = listOf(
            program(1, 10), program(2, 10, intent = "intent:1"), program(3, 10), program(4, 10),
            program(5, 20), program(6, 20, intent = null),
        )
        val limited = ChannelRules.programsByChannel(programs, perChannel = 2)
        assertEquals(listOf(1L, 3L), limited[10]?.map { it.id })
        assertEquals(listOf(5L), limited[20]?.map { it.id })
        val unlimited = ChannelRules.programsByChannel(programs, perChannel = 0)
        assertEquals(listOf(1L, 3L, 4L), unlimited[10]?.map { it.id })
    }

    @Test
    fun watchNextIsNewestFirstAndLimitedPerApp() {
        val list = listOf(
            program(1, -1, pkg = "a", engaged = 100),
            program(2, -1, pkg = "a", engaged = 300),
            program(3, -1, pkg = "b", engaged = 200),
            program(4, -1, pkg = "a", engaged = 250),
            program(5, -1, pkg = "b", engaged = 400, intent = null),
        )
        assertEquals(listOf(2L, 4L, 3L, 1L), ChannelRules.watchNext(list, perApp = 0).map { it.id })
        assertEquals(listOf(2L, 3L), ChannelRules.watchNext(list, perApp = 1).map { it.id })
    }
}

class ChannelCategorySyncTest {
    private val base = CategoryList(DefaultCategories.create())
    private val jellyfin = Channel(101, "org.jellyfin", "Next up", browsable = true)
    private val kodi = Channel(102, "org.kodi", "Suggestions", browsable = false)

    @Test
    fun oneHiddenCategoryPerChannel() {
        val synced = ChannelCategorySync.sync(base, listOf(jellyfin, kodi), enabled = true)
        val added = synced.categories.filter { it.type == CategoryType.CHANNEL }
        assertEquals(listOf(2101, 2102), added.map { it.id })
        assertEquals(listOf("org.jellyfinNext up", "org.kodiSuggestions"), added.map { it.channelUniqueId })
        assertTrue(added.all { !it.isVisible && it.scale == 1.2f })
        // Projectivy stores the channel's name as the title (its backups carry it).
        assertEquals(listOf("Next up", "Suggestions"), added.map { it.title })
        assertEquals(base.categories, synced.categories.take(base.categories.size))
        assertEquals(2102, synced.lastChannelId)
    }

    @Test
    fun syncingAgainChangesNothing() {
        val once = ChannelCategorySync.sync(base, listOf(jellyfin, kodi), enabled = true)
        assertSame(once, ChannelCategorySync.sync(once, listOf(kodi, jellyfin), enabled = true))
    }

    @Test
    fun existingCategoriesKeepTheirIdAndSettings() {
        val once = ChannelCategorySync.sync(base, listOf(jellyfin), enabled = true)
        val shown = once.copy(categories = once.categories.map { if (it.id == 2101) it.copy(isVisible = true) else it })
        val twice = ChannelCategorySync.sync(shown, listOf(kodi, jellyfin), enabled = true)
        val byUid = twice.categories.filter { it.type == CategoryType.CHANNEL }.associateBy { it.channelUniqueId }
        assertEquals(2101, byUid.getValue(jellyfin.uniqueId).id)
        assertTrue(byUid.getValue(jellyfin.uniqueId).isVisible)
        assertEquals(2102, byUid.getValue(kodi.uniqueId).id)
    }

    @Test
    fun idsKeepGrowingAfterChannelsWereRemoved() {
        val list = base.copy(lastChannelId = 2105)
        val synced = ChannelCategorySync.sync(list, listOf(jellyfin), enabled = true)
        assertEquals(2106, synced.categories.last().id)
    }

    @Test
    fun switchingChannelsOffRestoresTheOriginalList() {
        val on = ChannelCategorySync.sync(base, listOf(jellyfin, kodi), enabled = true)
        val off = ChannelCategorySync.sync(on, listOf(jellyfin, kodi), enabled = false)
        assertEquals(base.categories, off.categories)
        assertSame(base, ChannelCategorySync.sync(base, null, enabled = false))
    }

    @Test
    fun unreadableChannelsChangeNothingAndGoneChannelsStay() {
        val on = ChannelCategorySync.sync(base, listOf(jellyfin, kodi), enabled = true)
        assertSame(on, ChannelCategorySync.sync(on, null, enabled = true))
        assertSame(on, ChannelCategorySync.sync(on, listOf(kodi), enabled = true))
    }

    @Test
    fun untitledChannelCategoriesGetTheChannelName() {
        val on = ChannelCategorySync.sync(base, listOf(jellyfin, kodi), enabled = true)
        val untitled = on.copy(categories = on.categories.map { if (it.type == CategoryType.CHANNEL) it.copy(title = null) else it })
        val fixed = ChannelCategorySync.sync(untitled, listOf(jellyfin), enabled = true)
        val byUid = fixed.categories.filter { it.type == CategoryType.CHANNEL }.associateBy { it.channelUniqueId }
        assertEquals("Next up", byUid.getValue(jellyfin.uniqueId).title)
        // A channel that is not published now keeps no title; a stored title is never replaced.
        assertEquals(null, byUid.getValue(kodi.uniqueId).title)
        val renamed = on.copy(categories = on.categories.map { if (it.id == 2101) it.copy(title = "Mine") else it })
        assertSame(renamed, ChannelCategorySync.sync(renamed, listOf(jellyfin, kodi), enabled = true))
    }

    @Test
    fun duplicateChannelNamesShareOneCategory() {
        val twin = jellyfin.copy(id = 999)
        val synced = ChannelCategorySync.sync(base, listOf(jellyfin, twin), enabled = true)
        assertEquals(1, synced.categories.count { it.type == CategoryType.CHANNEL })
        assertTrue(synced.categories.none { it.id in CategoryIds.FIRST_USER until CategoryIds.FIRST_CHANNEL && it.type == CategoryType.CHANNEL })
    }
}

class TvInputsTest {
    @Test
    fun hardwareInputsCarryHw() {
        assertTrue(TvInputs.isHardwareId("com.droidlogic.tvinput/.services.Hdmi1InputService/HW5"))
        assertFalse(TvInputs.isHardwareId("com.google.android.videos/.livetv.TvInputService"))
    }

    @Test
    fun aCustomLabelWins() {
        assertEquals("Console", TvInputs.label("Console", "HDMI 1"))
        assertEquals("HDMI 1", TvInputs.label(" ", "HDMI 1"))
        assertEquals("HDMI 1", TvInputs.label(null, "HDMI 1"))
        assertEquals("", TvInputs.label(null, null))
    }
}
