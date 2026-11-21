/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings.backup.projectivy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.cygnusx1.qlauncher.data.ItemKeys

/**
 * The launchable apps on this device, read once from the package manager: every activity that
 * answers `MAIN` with `LEANBACK_LAUNCHER`, then `LAUNCHER` for packages without a TV activity.
 * Call from a background thread.
 */
class PackageManagerApps(context: Context) : InstalledApps {
    private val pm: PackageManager = context.packageManager

    /** Package → item key, TV activity preferred. */
    private val byPackage: Map<String, String> = run {
        val result = LinkedHashMap<String, String>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            val activities = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
            for (ri in activities) {
                val ai = ri.activityInfo ?: continue
                result.putIfAbsent(ai.packageName, ItemKeys.app(ai.packageName, ai.name))
            }
        }
        result
    }

    private val byId: Map<Int, String> = byPackage.entries.associate { (pkg, key) -> ProjectivyDecoder.appId(pkg) to key }

    override fun itemKeyForAppId(id: Int): String? = byId[id]

    override fun itemKeyForPackage(packageName: String): String? = byPackage[packageName]

    override fun isInstalled(packageName: String): Boolean = byPackage.containsKey(packageName) ||
        runCatching { pm.getPackageInfo(packageName, 0); true }.getOrDefault(false)
}
