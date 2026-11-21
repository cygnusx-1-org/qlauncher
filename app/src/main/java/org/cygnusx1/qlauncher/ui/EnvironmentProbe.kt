/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.cygnusx1.qlauncher.container
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.system.QLAccessibilityService
import org.cygnusx1.qlauncher.ui.settings.model.Environment
import java.util.Locale

/** Reads the device and permission state that settings rows depend on. Cheap; call on resume. */
object EnvironmentProbe {
    fun read(context: Context): Environment {
        val pm = context.packageManager
        return Environment(
            accessibilityEnabled = accessibilityEnabled(context),
            notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
            tvListingsGranted = ContextCompat.checkSelfPermission(context, PERMISSION_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED,
            // Preview channels and Watch Next, the rows QLauncher shows, exist from Android 8.
            tvProviderAvailable = Build.VERSION.SDK_INT >= 26 && pm.resolveContentProvider("android.media.tv", 0) != null,
            hasTvInputs = pm.hasSystemFeature(PackageManager.FEATURE_LIVE_TV),
            hasBattery = hasBattery(context),
        )
    }

    const val PERMISSION_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"

    fun accessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = ComponentName(context, QLAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun hasBattery(context: Context): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)
    }
}

/** Applies *Change application language* by wrapping an activity's base context. */
object LocaleHelper {
    fun wrap(base: Context): Context {
        val tag = runCatching { base.container.settings.current[Keys.General.language] }.getOrDefault("")
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /** On Android 13+ also tells the system, so system UI shows the app's language. */
    fun applySystem(context: Context, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            val lm = context.getSystemService(android.app.LocaleManager::class.java)
            lm?.applicationLocales = if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        }
    }
}
