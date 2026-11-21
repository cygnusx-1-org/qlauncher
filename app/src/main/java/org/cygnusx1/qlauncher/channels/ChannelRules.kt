/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.channels

import org.cygnusx1.qlauncher.data.Category
import org.cygnusx1.qlauncher.data.CategoryIds
import org.cygnusx1.qlauncher.data.CategoryList
import org.cygnusx1.qlauncher.data.CategoryType
import org.cygnusx1.qlauncher.shared.Channel
import org.cygnusx1.qlauncher.shared.Program

/** A channel row as read from the provider, before filtering. */
data class ChannelRow(
    val id: Long,
    val packageName: String?,
    val type: String?,
    val displayName: String?,
    val internalProviderId: String?,
    val browsable: Boolean,
    val logoUri: String? = null,
)

/** The channel and program rules Projectivy applies, as pure functions. */
object ChannelRules {
    const val TYPE_PREVIEW = "TYPE_PREVIEW"

    /**
     * *Enable channels* takes effect only once it has been set; until then it follows whether
     * `READ_TV_LISTINGS` is granted.
     */
    fun isEnabled(isSet: Boolean, stored: Boolean, permissionGranted: Boolean): Boolean =
        if (isSet) stored else permissionGranted

    /**
     * Preview channels only, without the system's own recommendation packages
     * (`….tvrecommendations`), one per package and internal provider id.
     */
    fun channels(rows: List<ChannelRow>): List<Channel> {
        val seen = HashSet<Pair<String, String>>()
        return rows.asSequence()
            .filter { it.type == TYPE_PREVIEW }
            .filter { val p = it.packageName; p != null && !p.contains(".tvrecommendations") }
            .filter { r -> r.internalProviderId == null || seen.add(r.packageName!! to r.internalProviderId) }
            .map { Channel(it.id, it.packageName!!, it.displayName.orEmpty(), it.browsable, it.logoUri) }
            .toList()
    }

    /**
     * Preview programs grouped by channel. Input is in provider order (weight, highest first);
     * a program with the same intent as an earlier one of its channel is dropped; each channel
     * keeps at most [perChannel] programs (0 = no limit).
     */
    fun programsByChannel(programs: List<Program>, perChannel: Int): Map<Long, List<Program>> {
        val seen = HashSet<Pair<Long, String>>()
        val out = LinkedHashMap<Long, MutableList<Program>>()
        for (p in programs) {
            val intent = p.intentUri ?: continue
            if (!seen.add(p.channelId to intent)) continue
            val list = out.getOrPut(p.channelId) { ArrayList() }
            if (perChannel > 0 && list.size >= perChannel) continue
            list += p
        }
        return out
    }

    /**
     * Watch Next, most recently engaged first, at most [perApp] entries per app (0 = no limit),
     * entries without an intent dropped.
     */
    fun watchNext(programs: List<Program>, perApp: Int): List<Program> {
        val counts = HashMap<String, Int>()
        return programs.asSequence()
            .filter { it.intentUri != null }
            .sortedByDescending { it.lastEngagementTimeMillis }
            .filter { p ->
                if (perApp <= 0) return@filter true
                val n = counts[p.packageName] ?: 0
                if (n >= perApp) false else {
                    counts[p.packageName] = n + 1
                    true
                }
            }
            .toList()
    }
}

/**
 * One hidden category per published channel, as Projectivy keeps them: type [CategoryType.CHANNEL],
 * ids from [CategoryIds.FIRST_CHANNEL], scale 1.2, not visible, keyed by
 * [Category.channelUniqueId] (package plus channel name). They are all removed when channels are
 * switched off. A category whose channel disappeared while channels are on stays, so the editor
 * can say the channel can no longer be read.
 */
object ChannelCategorySync {
    const val SCALE = 1.2f

    /**
     * @param channels the published channels, or null when they could not be read (then nothing
     *   is added or removed).
     * @return [list] itself when nothing changes.
     */
    fun sync(list: CategoryList, channels: List<Channel>?, enabled: Boolean): CategoryList {
        if (!enabled) {
            if (list.categories.none { it.type == CategoryType.CHANNEL }) return list
            return list.copy(categories = list.categories.filterNot { it.type == CategoryType.CHANNEL })
        }
        if (channels == null) return list
        val byUid = channels.distinctBy { it.uniqueId }.associateBy { it.uniqueId }
        // Projectivy stores the channel's name as the category's title; categories created
        // without one get it here.
        var titled = false
        val categories = list.categories.map { c ->
            val name = if (c.type == CategoryType.CHANNEL && c.title == null) c.channelUniqueId?.let { byUid[it] }?.displayName?.ifEmpty { null } else null
            if (name == null) c else c.copy(title = name).also { titled = true }
        }
        val existing = categories.filter { it.type == CategoryType.CHANNEL }.mapNotNullTo(HashSet()) { it.channelUniqueId }
        val missing = byUid.values.filter { it.uniqueId !in existing }
        if (missing.isEmpty()) return if (titled) list.copy(categories = categories) else list
        var nextId = maxOf(
            list.lastChannelId,
            list.categories.filter { it.id >= CategoryIds.FIRST_CHANNEL }.maxOfOrNull { it.id } ?: 0,
            CategoryIds.FIRST_CHANNEL - 1,
        )
        val added = missing.map { ch ->
            nextId += 1
            Category(
                id = nextId,
                type = CategoryType.CHANNEL,
                title = ch.displayName.ifEmpty { null },
                scale = SCALE,
                isVisible = false,
                channelUniqueId = ch.uniqueId,
            )
        }
        return list.copy(categories = categories + added, lastChannelId = nextId)
    }
}
