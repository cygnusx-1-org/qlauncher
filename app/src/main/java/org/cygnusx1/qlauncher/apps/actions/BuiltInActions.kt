/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.apps.actions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.annotation.StringRes
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.apps.PmCompat
import org.cygnusx1.qlauncher.data.CategoryIds
import org.cygnusx1.qlauncher.data.ItemKeys
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.SettingsRepository
import org.cygnusx1.qlauncher.settings.WallpaperProviders

/**
 * A built-in entry of the root panel and of the section categories (Settings, Display,
 * Applications, System, About).
 *
 * @property external started through [BuiltInActions.intent]; the others open QLauncher's own
 *   panels and pages, which the home screen maps by [id].
 * @property icon a Material Icons Outlined ligature for `MaterialIcon`.
 */
data class BuiltInAction(
    val id: String,
    @param:StringRes @get:StringRes val label: Int,
    val icon: String,
    val section: Int,
    val external: Boolean = false,
) {
    val key: String get() = ItemKeys.action(id)
}

/**
 * The built-in actions that exist on ordinary TV boxes, with their
 * availability checks. Projector and root tools are left out.
 */
class BuiltInActions internal constructor(
    private val context: Context,
    private val settings: SettingsRepository,
    /** True while channels are enabled and readable (Edit channels is listed then). */
    private val channelsAvailable: () -> Boolean,
    /** The system settings started in the system's own handler (`AppRepository.systemSettingsIntent`). */
    private val systemSettings: () -> Intent,
) {
    private val pm: PackageManager = context.packageManager

    /** The entries of a section category ([CategoryIds.SECTION_SETTINGS] .. [CategoryIds.SECTION_ABOUT]) that exist here. */
    fun section(categoryId: Int): List<BuiltInAction> = ALL.filter { it.section == categoryId && isAvailable(it.id) }

    /** Every available entry, grouped by section in panel order. */
    fun all(): Map<Int, List<BuiltInAction>> = SECTIONS.associateWith { section(it) }

    fun byId(id: String): BuiltInAction? = ALL.firstOrNull { it.id == id }

    fun isAvailable(id: String): Boolean = when (id) {
        EDIT_CHANNELS -> channelsAvailable()
        AMLOGIC_SETTINGS -> PmCompat.isInstalled(pm, AMLOGIC_PACKAGE)
        GOOGLE_SMART_HOME -> isGoogleTv() && PmCompat.resolveActivity(pm, smartHomeIntent()) != null
        SWITCH_PROFILE -> isGoogleTv() && PmCompat.resolveActivity(pm, profileIntent()) != null
        ABOUT_WALLPAPER -> settings.current[Keys.Wallpaper.provider].let {
            it != WallpaperProviders.NONE && it != WallpaperProviders.DYNAMIC_COLORS
        }
        else -> ALL.any { it.id == id }
    }

    /** The intent of an external action, or null for internal ones and missing apps. */
    fun intent(id: String): Intent? = when (id) {
        ANDROID_SETTINGS -> systemSettings()
        AMLOGIC_SETTINGS -> amlogicIntents().firstOrNull { PmCompat.resolveActivity(pm, it) != null } ?: amlogicIntents().first()
        GOOGLE_SMART_HOME -> smartHomeIntent()
        SWITCH_PROFILE -> profileIntent()
        else -> null
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Starts an external action; AmLogic falls back to its second settings activity. */
    fun launch(context: Context, id: String): Boolean = when (id) {
        AMLOGIC_SETTINGS -> amlogicIntents().any { PmCompat.start(context, it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        else -> intent(id)?.let { PmCompat.start(context, it) } ?: false
    }

    /** Google TV (`com.google.android.feature.AMATI_EXPERIENCE`). */
    fun isGoogleTv(): Boolean = pm.hasSystemFeature(FEATURE_GOOGLE_TV)

    private fun amlogicIntents() = listOf(
        Intent().setComponent(ComponentName(AMLOGIC_PACKAGE, "$AMLOGIC_PACKAGE.MainSettings")),
        Intent().setComponent(ComponentName(AMLOGIC_PACKAGE, "$AMLOGIC_PACKAGE.more.MorePrefFragmentActivity")),
    )

    private fun smartHomeIntent() = Intent(ACTION_SMART_HOME)
        .setComponent(ComponentName("com.google.android.apps.tv.dreamx", "com.google.android.libraries.tv.smarthome.core.SmartHomeMainActivity"))

    private fun profileIntent() = Intent()
        .setClassName("com.google.android.apps.tv.launcherx", "com.google.android.apps.tv.launcherx.profile.chooser.ProfileChooserActivity")

    // --- Screensaver (Autostart on boot, idle action) -------------------------------------------

    /**
     * The system screensaver can be started: the dream starter activity resolves, or a dream is
     * configured.
     */
    fun isScreensaverAvailable(): Boolean =
        PmCompat.resolveActivity(pm, screensaverIntent()) != null || dreamComponents().isNotEmpty()

    /** Starts the system screensaver (the Somnambulator activity of System UI). */
    fun startScreensaver(context: Context): Boolean = PmCompat.start(context, screensaverIntent())

    fun screensaverIntent(): Intent = Intent(Intent.ACTION_MAIN)
        .setClassName("com.android.systemui", "com.android.systemui.Somnambulator")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun dreamComponents(): List<String> = runCatching {
        Settings.Secure.getString(context.contentResolver, "screensaver_components")
    }.getOrNull().orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        const val ALL_APPS = "all_apps"
        const val EDIT_CATEGORIES = "edit_categories"
        const val EDIT_CHANNELS = "edit_channels"
        const val LAUNCHER_SETTINGS = "launcher_settings"
        const val ANDROID_SETTINGS = "android_settings"
        const val AMLOGIC_SETTINGS = "amlogic_settings"
        const val DISPLAY_PROFILES = "display_profiles"
        const val INSTALL_APPS = "install_apps"
        const val ACCESSIBILITY_SERVICE = "accessibility_service"
        const val GOOGLE_SMART_HOME = "google_smart_home"
        const val SWITCH_PROFILE = "switch_profile"
        const val ABOUT_APP = "about_app"
        const val ABOUT_DEVICE = "about_device"
        const val ABOUT_WALLPAPER = "about_wallpaper"

        const val AMLOGIC_PACKAGE = "com.droidlogic.tv.settings"
        const val FEATURE_GOOGLE_TV = "com.google.android.feature.AMATI_EXPERIENCE"
        const val ACTION_SMART_HOME = "com.google.android.libraries.tv.smarthome.intent.action.OPEN_SMART_HOME"

        val SECTIONS = listOf(
            CategoryIds.SECTION_SETTINGS,
            CategoryIds.SECTION_DISPLAY,
            CategoryIds.SECTION_APPLICATIONS,
            CategoryIds.SECTION_SYSTEM,
            CategoryIds.SECTION_ABOUT,
        )

        /** Every entry in panel order. */
        val ALL: List<BuiltInAction> = listOf(
            BuiltInAction(ALL_APPS, R.string.apps_action_all_apps, "apps", CategoryIds.SECTION_SETTINGS),
            BuiltInAction(EDIT_CATEGORIES, R.string.apps_action_edit_categories, "category", CategoryIds.SECTION_SETTINGS),
            BuiltInAction(EDIT_CHANNELS, R.string.apps_action_edit_channels, "live_tv", CategoryIds.SECTION_SETTINGS),
            BuiltInAction(LAUNCHER_SETTINGS, R.string.apps_action_launcher_settings, "settings", CategoryIds.SECTION_SETTINGS),
            BuiltInAction(ANDROID_SETTINGS, R.string.apps_action_android_settings, "settings_applications", CategoryIds.SECTION_SETTINGS, external = true),
            BuiltInAction(AMLOGIC_SETTINGS, R.string.apps_action_amlogic_settings, "tune", CategoryIds.SECTION_SETTINGS, external = true),
            BuiltInAction(DISPLAY_PROFILES, R.string.apps_action_display_profiles, "display_settings", CategoryIds.SECTION_DISPLAY),
            BuiltInAction(INSTALL_APPS, R.string.apps_action_install_apps, "get_app", CategoryIds.SECTION_APPLICATIONS),
            BuiltInAction(ACCESSIBILITY_SERVICE, R.string.apps_action_accessibility_service, "accessibility_new", CategoryIds.SECTION_SYSTEM),
            BuiltInAction(GOOGLE_SMART_HOME, R.string.apps_action_google_smart_home, "home", CategoryIds.SECTION_SYSTEM, external = true),
            BuiltInAction(SWITCH_PROFILE, R.string.apps_action_switch_profile, "switch_account", CategoryIds.SECTION_SYSTEM, external = true),
            BuiltInAction(ABOUT_APP, R.string.apps_action_about_app, "info", CategoryIds.SECTION_ABOUT),
            BuiltInAction(ABOUT_DEVICE, R.string.apps_action_about_device, "tv", CategoryIds.SECTION_ABOUT),
            BuiltInAction(ABOUT_WALLPAPER, R.string.apps_action_about_wallpaper, "wallpaper", CategoryIds.SECTION_ABOUT),
        )
    }
}
