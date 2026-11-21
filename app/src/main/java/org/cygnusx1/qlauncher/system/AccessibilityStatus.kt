/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.system

import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The state of [QLAccessibilityService], for the *Accessibility service* page and the reminder.
 * Lives in the process whether or not the service runs.
 */
class AccessibilityStatus(private val context: Context) {
    private val component = ComponentName(context, QLAccessibilityService::class.java)

    private val _enabled = MutableStateFlow(readEnabled())
    private val _started = MutableStateFlow(false)
    private val _foregroundPackage = MutableStateFlow<String?>(null)

    /** *Enabled*: the user switched the service on (`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`). */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** *Started*: the system has bound the service and it is running now. */
    val started: StateFlow<Boolean> = _started.asStateFlow()

    /**
     * The package of the activity in the foreground, as the service last saw it; null while the
     * service is not running. Kept in memory only.
     */
    val foregroundPackage: StateFlow<String?> = _foregroundPackage.asStateFlow()

    init {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer,
        )
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, observer,
        )
    }

    /** Re-reads the secure setting (also done on every change of it). */
    fun refresh() {
        _enabled.value = readEnabled()
    }

    internal fun onServiceStarted() {
        _started.value = true
        refresh()
    }

    internal fun onServiceStopped() {
        _started.value = false
        _foregroundPackage.value = null
        refresh()
    }

    internal fun onForegroundPackage(packageName: String) {
        _foregroundPackage.value = packageName
    }

    private fun readEnabled(): Boolean {
        val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        if (list.isNullOrBlank()) return false
        return list.split(':').any { ComponentName.unflattenFromString(it.trim()) == component }
    }
}
