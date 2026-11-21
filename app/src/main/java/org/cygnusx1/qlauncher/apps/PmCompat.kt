/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Bundle
import android.util.Log

/** API-level wrappers around the PackageManager calls this subsystem uses. */
internal object PmCompat {
    fun queryActivities(pm: PackageManager, intent: Intent, flags: Int = 0): List<ResolveInfo> = try {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, flags)
        }
    } catch (e: RuntimeException) {
        // A dead package manager or a too-large result on a crowded device.
        Log.w(TAG, "queryIntentActivities failed", e)
        emptyList()
    }

    fun resolveActivity(pm: PackageManager, intent: Intent, flags: Int = 0): ResolveInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, flags)
        }
    } catch (e: RuntimeException) {
        null
    }

    fun packageInfo(pm: PackageManager, packageName: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    fun applicationInfo(pm: PackageManager, packageName: String): ApplicationInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    fun isInstalled(pm: PackageManager, packageName: String): Boolean = packageInfo(pm, packageName) != null

    fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }

    /** Starts [intent], adding `FLAG_ACTIVITY_NEW_TASK` when [context] is not an activity. */
    fun start(context: Context, intent: Intent, options: Bundle? = null): Boolean {
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent, options)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity for $intent")
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to start $intent", e)
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot start $intent", e)
            false
        }
    }

    private const val TAG = "QLApps"
}
