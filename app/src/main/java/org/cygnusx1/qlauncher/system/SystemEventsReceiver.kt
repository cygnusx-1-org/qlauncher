/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import org.cygnusx1.qlauncher.container

/**
 * The manifest receiver for boot, locale, USB and storage broadcasts. It records the boot for
 * autostart and republishes everything on [SystemModule.events]; it starts nothing itself
 * (autostart goes through the accessibility service, which may start activities from the
 * background).
 */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = toEvent(intent) ?: return
        val system = context.container.system
        if (event == SystemEvent.BootCompleted) system.boot.onBootCompleted()
        if (event.clearsLabelCaches) system.onLocaleChanged()
        system.eventBus.emit(event)
    }

    companion object {
        fun toEvent(intent: Intent): SystemEvent? = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> SystemEvent.BootCompleted
            Intent.ACTION_LOCALE_CHANGED -> SystemEvent.LocaleChanged
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> SystemEvent.UsbDeviceChanged(attached = true)
            UsbManager.ACTION_USB_DEVICE_DETACHED -> SystemEvent.UsbDeviceChanged(attached = false)
            Intent.ACTION_MEDIA_MOUNTED -> SystemEvent.StorageChanged(mounted = true, path = intent.data?.path)
            Intent.ACTION_MEDIA_UNMOUNTED -> SystemEvent.StorageChanged(mounted = false, path = intent.data?.path)
            else -> null
        }
    }
}
