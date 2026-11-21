/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.channels

import android.content.res.Resources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.data.Category
import org.cygnusx1.qlauncher.data.CategoryIds
import org.cygnusx1.qlauncher.data.CategoryStore
import org.cygnusx1.qlauncher.data.CategoryType
import org.cygnusx1.qlauncher.shared.Channel

/** An app that publishes channels, as the *Edit channels* panel lists it. */
data class ChannelApp(val packageName: String, val label: String, val channels: List<Channel>) {
    /** "5 Channels", or the channel's name when there is only one ("Kodi — Suggestions"). */
    fun subtitle(res: Resources): String =
        channels.singleOrNull()?.displayName ?: res.getQuantityString(R.plurals.apps_channel_count, channels.size, channels.size)
}

/** One channel of an app in the panel: its category and whether that row is shown on Home. */
data class ChannelEntry(
    val channel: Channel,
    val categoryId: Int?,
    val visible: Boolean,
    /** The system lets the channel be shown; false gets the hidden marker. */
    val browsable: Boolean,
)

/**
 * Keeps one hidden category per published channel ([ChannelCategorySync]) and backs the
 * *Edit channels* panel and the app menu's *Channels* switches.
 */
class ChannelEditor internal constructor(
    private val scope: CoroutineScope,
    private val categories: CategoryStore,
    private val channels: ChannelRepository,
    private val appLabel: (String) -> String,
) {
    init {
        scope.launch {
            combine(channels.enabled, channels.loadedChannels) { e, l -> e to l }
                .distinctUntilChanged()
                .collect { (enabled, list) -> sync(enabled, list) }
        }
    }

    private suspend fun sync(enabled: Boolean, list: List<Channel>?) {
        categories.edit { current -> ChannelCategorySync.sync(current, list, enabled) }
    }

    /** Apps that publish channels, by label. The panel lists *Watch Next* above them. */
    val apps: Flow<List<ChannelApp>> = channels.channels.map { list ->
        list.groupBy { it.packageName }
            .map { (pkg, l) -> ChannelApp(pkg, appLabel(pkg), l) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    /** [packageName]'s channels with their categories (the app panel and the app menu). */
    fun entries(packageName: String): Flow<List<ChannelEntry>> =
        combine(channels.channels, categories.categories) { list, cats ->
            list.filter { it.packageName == packageName }.map { ch ->
                val cat = categoryOf(cats, ch)
                ChannelEntry(ch, cat?.id, cat?.isVisible == true, ch.browsable)
            }
        }

    /** True when [packageName] publishes at least one channel (the app menu's *Channels* row). */
    fun publishesChannels(packageName: String): Boolean = channels.channels.value.any { it.packageName == packageName }

    fun categoryOf(channel: Channel): Category? = categoryOf(categories.categories.value, channel)

    private fun categoryOf(cats: List<Category>, ch: Channel): Category? =
        cats.firstOrNull { it.type == CategoryType.CHANNEL && it.channelUniqueId == ch.uniqueId }

    /**
     * Shows or hides [channel]'s row on Home. Showing a channel the system keeps hidden also asks
     * the system to make it browsable.
     */
    fun setVisible(channel: Channel, visible: Boolean) {
        scope.launch {
            categories.edit { list ->
                val synced = ChannelCategorySync.sync(list, listOf(channel), enabled = true)
                synced.copy(
                    categories = synced.categories.map {
                        if (it.type == CategoryType.CHANNEL && it.channelUniqueId == channel.uniqueId) it.copy(isVisible = visible) else it
                    },
                )
            }
            if (visible && !channel.browsable) channels.requestBrowsable(channel.id)
        }
    }

    /** Watch Next's own visibility (the first row of the panel). */
    val watchNextVisible: Flow<Boolean> = categories.categories.map { cats ->
        cats.firstOrNull { it.id == CategoryIds.WATCH_NEXT }?.isVisible == true
    }.distinctUntilChanged()

    fun setWatchNextVisible(visible: Boolean) = categories.update(CategoryIds.WATCH_NEXT) { it.copy(isVisible = visible) }
}
