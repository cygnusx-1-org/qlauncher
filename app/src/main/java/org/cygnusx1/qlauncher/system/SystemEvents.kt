/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.system

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** System broadcasts the home screen reacts to, as published by [SystemEventsReceiver]. */
sealed interface SystemEvent {
    /** The device finished booting (`BOOT_COMPLETED` or `LOCKED_BOOT_COMPLETED`). */
    data object BootCompleted : SystemEvent

    /** The system language changed: cached app labels are stale. */
    data object LocaleChanged : SystemEvent

    /** A USB device was attached or detached: refresh the home content while in front. */
    data class UsbDeviceChanged(val attached: Boolean) : SystemEvent

    /** A storage volume was mounted or unmounted ([path] is its mount point when known). */
    data class StorageChanged(val mounted: Boolean, val path: String?) : SystemEvent

    /** Whether the home content (apps, folders, wallpaper sources) should be refreshed. */
    val refreshesContent: Boolean
        get() = this is UsbDeviceChanged || this is StorageChanged || this is LocaleChanged

    /** Whether cached app labels should be dropped. */
    val clearsLabelCaches: Boolean get() = this is LocaleChanged
}

/**
 * The process-wide stream of [SystemEvent]s. Events are not replayed: a collector sees what
 * happens while it collects. Emitting never suspends; the oldest of more than 16 unread events
 * is dropped.
 */
class SystemEventBus {
    private val flow = MutableSharedFlow<SystemEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<SystemEvent> = flow.asSharedFlow()

    fun emit(event: SystemEvent) {
        flow.tryEmit(event)
    }
}

/**
 * Remembers when the boot broadcast arrived, for autostart: the accessibility service treats its
 * connection as "shortly after boot" when it comes within [WINDOW_MILLIS] of the device starting
 * (`SystemClock.elapsedRealtime()`) or of the boot broadcast, whichever is later.
 */
class BootTracker(private val clock: MonotonicClock = MonotonicClock.Elapsed) {
    @Volatile
    var bootReceivedAt: Long? = null
        private set

    fun onBootCompleted() {
        bootReceivedAt = clock.nowMillis()
    }

    fun isShortlyAfterBoot(now: Long = clock.nowMillis()): Boolean {
        if (now < WINDOW_MILLIS) return true
        val received = bootReceivedAt ?: return false
        return now - received in 0 until WINDOW_MILLIS
    }

    companion object {
        /** Projectivy autostarts when its service connects within 90 s of boot. */
        const val WINDOW_MILLIS = 90_000L
    }
}
