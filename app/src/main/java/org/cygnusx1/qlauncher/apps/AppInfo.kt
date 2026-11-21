/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.apps

import android.content.ComponentName

/**
 * One launchable activity.
 *
 * @property label the activity's own label; [customTitle] is the user's rename
 *   (`ItemOverride.title`), kept apart so the original stays available.
 * @property isTv the activity answers `LEANBACK_LAUNCHER` (a TV app, shown with its banner). A
 *   package with any TV activity never appears in the mobile list, so [isMobile] is the opposite.
 * @property hasActivityBanner / [hasAppBanner] whether a banner exists; with neither, a banner
 *   request gets the icon (see [org.cygnusx1.qlauncher.apps.image.AppImageSource]).
 */
data class AppInfo(
    val key: String,
    val packageName: String,
    val className: String,
    val label: String,
    val customTitle: String? = null,
    val isTv: Boolean,
    val installTime: Long = 0L,
    val lastUpdateTime: Long = 0L,
    val hasActivityBanner: Boolean = false,
    val hasAppBanner: Boolean = false,
    val isSystem: Boolean = false,
) {
    val isMobile: Boolean get() = !isTv

    /** What a card shows: the user's title when set, otherwise the label. */
    val title: String get() = customTitle?.takeIf { it.isNotBlank() } ?: label

    val hasBanner: Boolean get() = hasActivityBanner || hasAppBanner

    val component: ComponentName get() = ComponentName(packageName, className)
}

/** An activity as found by a `MAIN` query, before it becomes an [AppInfo]. */
data class ScannedActivity(
    val packageName: String,
    val className: String,
    val label: String,
    /** The resolve info says the filter has `CATEGORY_DEFAULT`. */
    val isDefault: Boolean = false,
    val installTime: Long = 0L,
    val lastUpdateTime: Long = 0L,
    val hasActivityBanner: Boolean = false,
    val hasAppBanner: Boolean = false,
    val isSystem: Boolean = false,
)

/**
 * Builds the TV and mobile lists from the two `MAIN` queries, the way Projectivy does:
 *
 * - our own package is left out;
 * - a package with any `LEANBACK_LAUNCHER` activity is a TV app only, never a mobile app;
 * - within a list, one activity per package, unless a further one is a default activity;
 * - each list is ordered by label (case-insensitive), which is the starting "custom" order.
 */
object AppListBuilder {
    data class Lists(val tv: List<AppInfo>, val mobile: List<AppInfo>) {
        /** TV list, then mobile list. */
        val all: List<AppInfo> get() = tv + mobile
    }

    fun build(tv: List<ScannedActivity>, mobile: List<ScannedActivity>, ownPackage: String): Lists {
        val tvPackages = tv.mapTo(HashSet()) { it.packageName }
        val tvApps = pick(tv.filter { it.packageName != ownPackage }, isTv = true)
        val mobileApps = pick(
            mobile.filter { it.packageName != ownPackage && it.packageName !in tvPackages },
            isTv = false,
        )
        return Lists(tvApps, mobileApps)
    }

    private fun pick(list: List<ScannedActivity>, isTv: Boolean): List<AppInfo> {
        val seenPackages = HashSet<String>()
        val seenComponents = HashSet<String>()
        val out = ArrayList<AppInfo>(list.size)
        for (a in list) {
            val key = AppKeys.app(a.packageName, a.className)
            if (!seenComponents.add(key)) continue
            if (!seenPackages.add(a.packageName) && !a.isDefault) continue
            out += AppInfo(
                key = key,
                packageName = a.packageName,
                className = AppKeys.fullClassName(a.packageName, a.className),
                label = a.label,
                isTv = isTv,
                installTime = a.installTime,
                lastUpdateTime = a.lastUpdateTime,
                hasActivityBanner = a.hasActivityBanner,
                hasAppBanner = a.hasAppBanner,
                isSystem = a.isSystem,
            )
        }
        return out.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }
}
