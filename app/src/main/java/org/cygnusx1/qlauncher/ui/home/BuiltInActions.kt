/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.home

import androidx.annotation.StringRes
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.data.CategoryIds

/** The root panel's entries, which are also the cards of the five section categories. */
enum class BuiltInAction(val id: String, @param:StringRes val title: Int, val icon: String, val section: Int) {
    AllApps("all_apps", R.string.home_action_all_apps, "apps", CategoryIds.SECTION_SETTINGS),
    EditCategories("edit_categories", R.string.home_action_edit_categories, "list", CategoryIds.SECTION_SETTINGS),
    EditChannels("edit_channels", R.string.home_action_edit_channels, "live_tv", CategoryIds.SECTION_SETTINGS),
    LauncherSettings("launcher_settings", R.string.home_action_launcher_settings, "settings", CategoryIds.SECTION_SETTINGS),
    AndroidSettings("android_settings", R.string.home_action_android_settings, "android", CategoryIds.SECTION_SETTINGS),
    AmlogicSettings("amlogic_settings", R.string.home_action_amlogic_settings, "settings_suggest", CategoryIds.SECTION_SETTINGS),
    DisplayProfiles("display_profiles", R.string.home_action_display_profiles, "tune", CategoryIds.SECTION_DISPLAY),
    InstallApps("install_apps", R.string.home_action_install_apps, "add_box", CategoryIds.SECTION_APPLICATIONS),
    Accessibility("accessibility_service", R.string.home_action_accessibility, "accessibility_new", CategoryIds.SECTION_SYSTEM),
    SmartHome("google_smart_home", R.string.home_action_smart_home, "home", CategoryIds.SECTION_SYSTEM),
    SwitchProfile("switch_profile", R.string.home_action_switch_profile, "account_circle", CategoryIds.SECTION_SYSTEM),
    AboutApp("about_app", R.string.home_action_about_app, "info", CategoryIds.SECTION_ABOUT),
    AboutDevice("about_device", R.string.home_action_about_device, "tv", CategoryIds.SECTION_ABOUT),
    AboutWallpaper("about_wallpaper", R.string.home_action_about_wallpaper, "wallpaper", CategoryIds.SECTION_ABOUT),
    ;

    val key: String get() = "action:$id"

    companion object {
        fun byKey(key: String): BuiltInAction? = entries.firstOrNull { it.key == key }

        /** Section headers of the root panel, in order. */
        val sections = listOf(
            CategoryIds.SECTION_SETTINGS to R.string.home_cat_settings,
            CategoryIds.SECTION_DISPLAY to R.string.home_cat_display,
            CategoryIds.SECTION_APPLICATIONS to R.string.home_cat_applications,
            CategoryIds.SECTION_SYSTEM to R.string.home_cat_system,
            CategoryIds.SECTION_ABOUT to R.string.home_cat_about,
        )
    }
}

/** Which conditional actions exist on this device; filled by the activity. */
data class ActionAvailability(
    val channels: Boolean = false,
    val amlogic: Boolean = false,
    val smartHome: Boolean = false,
    val switchProfile: Boolean = false,
    val aboutWallpaper: Boolean = true,
) {
    fun available(a: BuiltInAction): Boolean = when (a) {
        BuiltInAction.EditChannels -> channels
        BuiltInAction.AmlogicSettings -> amlogic
        BuiltInAction.SmartHome -> smartHome
        BuiltInAction.SwitchProfile -> switchProfile
        BuiltInAction.AboutWallpaper -> aboutWallpaper
        else -> true
    }

    fun forSection(section: Int): List<BuiltInAction> = BuiltInAction.entries.filter { it.section == section && available(it) }
}
