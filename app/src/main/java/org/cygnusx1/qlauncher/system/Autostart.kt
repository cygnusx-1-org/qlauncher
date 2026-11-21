/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.system

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.SettingsRepository

/**
 * *Power › Autostart on boot*: after a boot (and, with *…and upon resuming from standby*, after the
 * screen turns on) wait *Autostart delay* seconds and launch [Keys.Power.autostartSource]: empty
 * for none, [SOURCE_SCREENSAVER], or an app item key `app:<package>/<class>`.
 *
 * The accessibility service runs it when it connects shortly after boot (see [BootTracker]); the
 * home screen can run it instead when the service is off ([SystemModule.onHomeResumed]). Each
 * boot autostarts at most once, whichever gets there first.
 */
class Autostart(
    private val context: Context,
    private val settings: SettingsRepository,
    private val boot: BootTracker,
    private val actions: SystemActions,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** The configured target, or null for none. */
    fun source(): String? = settings.current[Keys.Power.autostartSource].trim().takeIf { it.isNotEmpty() }

    fun delayMillis(): Long = settings.current[Keys.Power.autostartDelay].coerceAtLeast(0) * 1_000L

    fun onResumeEnabled(): Boolean = settings.current[Keys.Power.autostartOnResume]

    /** True when the device booted moments ago and no autostart has run for this boot. */
    fun isBootPending(): Boolean = source() != null && boot.isShortlyAfterBoot() && prefs.getString(KEY_LAST_BOOT, null) != bootId()

    /**
     * Marks this boot's autostart as taken. Returns false when it already was. Blocking disk
     * write: call off the main thread.
     */
    @Synchronized
    fun claimBoot(): Boolean {
        val id = bootId()
        if (prefs.getString(KEY_LAST_BOOT, null) == id) return false
        return prefs.edit().putString(KEY_LAST_BOOT, id).commit()
    }

    /** Launches the configured target from [caller] (the service or an activity). */
    fun launch(caller: Context): Boolean {
        val src = source() ?: return false
        val ok = when {
            src == SOURCE_SCREENSAVER -> actions.startScreensaver()
            src.startsWith("input:") -> {
                Log.i(TAG, "TV inputs are not supported on this device: $src")
                false
            }
            else -> actions.launchAppItem(src, caller)
        }
        if (!ok) Log.w(TAG, "Autostart target did not start: $src")
        return ok
    }

    /** `Settings.Global.BOOT_COUNT`, or the boot time to the minute when the count is missing. */
    private fun bootId(): String {
        val count = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (count >= 0) return "count:$count"
        return "time:" + (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000L
    }

    companion object {
        const val SOURCE_SCREENSAVER = "screensaver"
        private const val TAG = "QLAutostart"
        private const val PREFS = "system_state"
        private const val KEY_LAST_BOOT = "autostart_last_boot"
    }
}
