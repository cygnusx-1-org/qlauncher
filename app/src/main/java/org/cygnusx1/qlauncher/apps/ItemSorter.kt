/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.apps

import org.cygnusx1.qlauncher.data.ItemOverride
import org.cygnusx1.qlauncher.data.SortOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * The category sort orders (Projectivy's values, [SortOrder]):
 *
 * | Value | Order |
 * |---|---|
 * | 0 custom | the order given (the category's stored order) |
 * | 1 name | case-insensitive title |
 * | 2 last used | most recent launch first |
 * | 3 most used | most launches first |
 * | 4 random | shuffled once per launcher start, and again when the order is selected |
 *
 * Every order is stable: items that compare equal keep their custom order, so never-launched
 * apps stay in custom order after the launched ones.
 *
 * @param sessionSeed the seed of this launcher start; one instance lives per process.
 */
class ItemSorter(private val sessionSeed: Long = Random.nextLong()) {
    private val reshuffled = ConcurrentHashMap<Int, Long>()

    /** Call when the user selects *Random* for [categoryId], to deal a new order. */
    fun reshuffle(categoryId: Int) {
        reshuffled[categoryId] = Random.nextLong()
    }

    fun seedFor(categoryId: Int): Long = reshuffled[categoryId] ?: (sessionSeed xor (categoryId.toLong() * -7046029254386353131L))

    /**
     * @param keyOf the item key, used for usage lookups and the random order.
     * @param titleOf the title the name order compares.
     * @param usage the item's override (launch count and time), null when never launched.
     */
    fun <T> sort(
        items: List<T>,
        order: Int,
        categoryId: Int,
        keyOf: (T) -> String,
        titleOf: (T) -> String,
        usage: (String) -> ItemOverride?,
    ): List<T> = SortOrders.sort(items, order, seedFor(categoryId), keyOf, titleOf, usage)

    /** Sorts plain item keys. */
    fun sortKeys(
        keys: List<String>,
        order: Int,
        categoryId: Int,
        titleOf: (String) -> String,
        usage: (String) -> ItemOverride?,
    ): List<String> = sort(keys, order, categoryId, { it }, titleOf, usage)
}

/** The orders themselves, as pure functions. */
object SortOrders {
    fun <T> sort(
        items: List<T>,
        order: Int,
        seed: Long,
        keyOf: (T) -> String,
        titleOf: (T) -> String,
        usage: (String) -> ItemOverride?,
    ): List<T> = when (order) {
        SortOrder.NAME -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, titleOf))
        SortOrder.LAST_USED -> items.sortedByDescending { usage(keyOf(it))?.lastLaunch ?: 0L }
        SortOrder.MOST_USED -> items.sortedByDescending { usage(keyOf(it))?.launchCount ?: 0 }
        SortOrder.RANDOM -> shuffled(items, seed, keyOf)
        else -> items
    }

    /**
     * A shuffle that depends only on the seed and each item's key, so adding or removing one item
     * leaves the relative order of the others as it was.
     */
    fun <T> shuffled(items: List<T>, seed: Long, keyOf: (T) -> String): List<T> =
        items.sortedBy { mix(seed, keyOf(it)) }

    private fun mix(seed: Long, key: String): Long {
        // SplitMix64 finaliser over the seed and the key's hash.
        var z = seed + key.hashCode().toLong() * -7046029254386353131L
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }
}

/** How a row's item list is put together from what exists and what the category stores. */
object CategoryItems {
    /**
     * A category that lists everything of one kind (TV apps, mobile apps, shortcuts, inputs, live
     * TV, a channel's programs): the stored custom order first, keeping only items that still
     * exist, then every other existing item in [available] order; hidden items removed.
     */
    fun merge(stored: List<String>, available: List<String>, hidden: Collection<String>): List<String> {
        val availableSet = available.toHashSet()
        val hiddenSet = hidden.toHashSet()
        val out = LinkedHashSet<String>(available.size)
        for (k in stored) if (k in availableSet && k !in hiddenSet) out += k
        for (k in available) if (k !in hiddenSet) out += k
        return out.toList()
    }

    /** A hand-made list (Favorites, user categories): its own items that still exist, hidden removed. */
    fun manual(stored: List<String>, hidden: Collection<String>, exists: (String) -> Boolean): List<String> {
        val hiddenSet = hidden.toHashSet()
        return stored.asSequence().distinct().filter { it !in hiddenSet && exists(it) }.toList()
    }
}
