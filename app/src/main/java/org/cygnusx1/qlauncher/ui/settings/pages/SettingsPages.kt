/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.settings.pages

import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.settings.Badge
import org.cygnusx1.qlauncher.settings.CardRatio
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.ui.settings.model.LeftPane
import org.cygnusx1.qlauncher.ui.settings.model.Option
import org.cygnusx1.qlauncher.ui.settings.model.Page
import org.cygnusx1.qlauncher.ui.settings.model.Row
import org.cygnusx1.qlauncher.ui.settings.model.RowContext
import org.cygnusx1.qlauncher.ui.settings.model.ValueFormat
import org.cygnusx1.qlauncher.ui.settings.model.raw
import org.cygnusx1.qlauncher.ui.settings.model.text

/** Page ids. */
object PageIds {
    const val ROOT = "root"
    const val GENERAL = "general"
    const val APPEARANCE = "appearance"
    const val CATEGORIES = "appearance/categories"
    const val CARDS = "appearance/cards"
    const val FOCUSED_CARD = "appearance/cards/focused"
    const val PROGRAM_CARDS = "appearance/cards/program"
    const val ICON_SET = "appearance/cards/icon_set"
    const val ICON_SET_ADD = "appearance/cards/icon_set/add"
    const val WALLPAPER = "appearance/wallpaper"
    const val WALLPAPER_CONFIGURE = "appearance/wallpaper/configure"
    const val PICTURE_ADJUSTMENT = "appearance/wallpaper/adjustment"
    const val VIDEO_WALLPAPER = "appearance/wallpaper/video"
    const val STATUS_BAR = "appearance/status_bar"
    const val CLOCK = "appearance/status_bar/clock"
    const val ICONS = "appearance/status_bar/icons"
    const val LEFT_HEADERS = "appearance/left_headers"
    const val IDLE = "appearance/idle"
    const val FONT = "appearance/font"
    const val THEMES = "appearance/themes"
    const val POWER = "power"
    const val PARENTAL = "parental"
    const val REMOTE = "remote"
    const val ACCESSIBILITY = "accessibility"
    const val ACCESSIBILITY_CONSENT = "accessibility/consent"
    const val DISPLAY_PROFILES = "display_profiles"
    const val DISPLAY_PROFILES_LIST = "display_profiles/list"
    const val DISPLAY_PROFILE = "display_profiles/profile/"
    const val MANAGE = "manage"
    const val ABOUT_APP = "about/app"
    const val ABOUT_LIBS = "about/libs"
    const val ABOUT_DEVICE = "about/device"
    const val ABOUT_WALLPAPER = "about/wallpaper"
    const val INSTALL_APPS = "install"
}

/**
 * A registry of page builders. Subsystems add pages that need their own data (wallpaper configure,
 * display profiles, about, install) through [register].
 */
object SettingsPages {
    private val extra = mutableMapOf<String, (PageEnv, String) -> Page?>()

    /** Registers a builder for ids equal to, or starting with, [prefix]. */
    fun register(prefix: String, builder: (PageEnv, String) -> Page?) {
        extra[prefix] = builder
    }

    fun build(id: String, env: PageEnv): Page? {
        core(id, env)?.let { return it }
        extra[id]?.invoke(env, id)?.let { return it }
        // The longest registered prefix wins (display_profiles/profile/… over display_profiles).
        return extra.entries.filter { id.startsWith(it.key) }.maxByOrNull { it.key.length }?.value?.invoke(env, id)
    }

    private val settings = text(R.string.settings_breadcrumb)

    private fun core(id: String, env: PageEnv): Page? = when (id) {
        PageIds.ROOT -> Page(
            id, settings, text(R.string.settings_root_title), text(R.string.settings_root_description),
            icon = "settings", legend = true,
            rows = {
                listOf(
                    nav("general", R.string.settings_general, PageIds.GENERAL, "settings_applications"),
                    nav("appearance", R.string.settings_appearance, PageIds.APPEARANCE, "dashboard"),
                    nav("power", R.string.settings_power, PageIds.POWER, "power_settings_new"),
                    nav("parental", R.string.settings_parental, PageIds.PARENTAL, "supervisor_account"),
                    nav("remote", R.string.settings_remote, PageIds.REMOTE, "settings_remote"),
                    nav("accessibility", R.string.settings_accessibility, PageIds.ACCESSIBILITY, "accessibility_new"),
                    nav("display_profiles", R.string.settings_display_profiles, PageIds.DISPLAY_PROFILES, "settings_brightness"),
                    nav("manage", R.string.settings_manage, PageIds.MANAGE, "settings_backup_restore"),
                )
            },
        )

        PageIds.GENERAL -> Page(
            id, settings, text(R.string.settings_general), text(R.string.settings_launcher_related), icon = "settings",
            rows = { _ ->
                val channelsShown: (RowContext) -> Boolean = { c -> c.env.tvProviderAvailable && !c.env.tvListingsPermanentlyDenied }
                listOf(
                    Row.Dropdown(
                        id = Keys.General.language.name,
                        title = text(R.string.settings_language),
                        options = { _ -> Languages.all.map { Option(if (it.first.isEmpty()) text(R.string.settings_language_system) else raw(it.second)) } },
                        selected = { c -> Languages.all.indexOfFirst { it.first == c.settings[Keys.General.language] }.coerceAtLeast(0) },
                        onSelect = { i -> env.actions.setLanguage(Languages.all[i].first) },
                        // Following the system shows no subtitle, as Projectivy's row does.
                        description = { c -> Languages.all.firstOrNull { it.first == c.settings[Keys.General.language] && it.first.isNotEmpty() }?.let { raw(it.second) } },
                        icon = "translate",
                    ),
                    Row.Action("default_launcher", text(R.string.settings_default_launcher), { env.actions.openDefaultLauncherSettings() }, icon = "home"),
                    toggle(Keys.General.overrideHome, R.string.settings_override_home, R.string.settings_override_home_desc),
                    toggle(
                        Keys.General.enableChannels, R.string.settings_enable_channels, R.string.settings_enable_channels_desc,
                        visible = channelsShown,
                        effective = { c -> if (c.settings.isSet(Keys.General.enableChannels)) c.settings[Keys.General.enableChannels] else c.env.tvListingsGranted },
                    ),
                    slider(Keys.General.programsPerChannel, R.string.settings_programs_per_channel, visible = channelsShown),
                    toggle(Keys.General.crashErrorReports, R.string.settings_crash_error_reports, R.string.settings_reports_desc),
                    toggle(Keys.General.checkUpdates, R.string.settings_check_updates),
                )
            },
        )

        PageIds.APPEARANCE -> Page(
            id, settings, text(R.string.settings_appearance), text(R.string.settings_launcher_related), icon = "settings",
            rows = {
                listOf(
                    nav("categories", R.string.settings_categories_channels, PageIds.CATEGORIES, "list"),
                    nav("cards", R.string.settings_cards, PageIds.CARDS, "crop_16_9"),
                    nav("wallpaper", R.string.settings_wallpaper, PageIds.WALLPAPER, "wallpaper"),
                    nav("status_bar", R.string.settings_status_bar, PageIds.STATUS_BAR, "horizontal_rule"),
                    nav("left_headers", R.string.settings_left_headers, PageIds.LEFT_HEADERS, "vertical_split"),
                    nav("idle", R.string.settings_idle_mode, PageIds.IDLE, "hourglass_full"),
                    nav("font", R.string.settings_font, PageIds.FONT, "font_download"),
                    Row.Nav("themes", text(R.string.settings_themes), PageIds.THEMES, "format_paint", { _ -> text(R.string.settings_themes_desc) }, badges = setOf(Badge.Experimental)),
                    toggle(Keys.Look.loadCardsWhileScrolling, R.string.settings_load_cards_scrolling, R.string.settings_load_cards_scrolling_desc),
                    color(Keys.Look.accent, R.string.settings_accent, R.string.settings_accent_desc),
                )
            },
        )

        PageIds.CATEGORIES -> Page(
            id, settings, text(R.string.settings_categories_channels), leftPane = LeftPane.LivePreview, icon = "list",
            rows = {
                listOf(
                    toggle(Keys.Categories.showTitle, R.string.settings_show_title),
                    toggle(Keys.Categories.showIcon, R.string.settings_show_icon_title, enabled = { c -> c.settings[Keys.Categories.showTitle] }),
                    slider(Keys.Categories.rowsSpacing, R.string.settings_rows_spacing),
                    toggle(Keys.Categories.fixedPosition, R.string.settings_fixed_position),
                    toggle(Keys.Categories.hideRowsAbove, R.string.settings_hide_rows_above, enabled = { c -> c.settings[Keys.Categories.fixedPosition] }),
                    toggle(Keys.Categories.rememberFocus, R.string.settings_remember_focus),
                    slider(Keys.Categories.unselectedOpacity, R.string.settings_unselected_opacity),
                    slider(Keys.Categories.topMargin, R.string.settings_top_margin, centred = true),
                    slider(Keys.Categories.startMargin, R.string.settings_start_margin, centred = true),
                    slider(Keys.Categories.endMargin, R.string.settings_end_margin, centred = true),
                    slider(Keys.Categories.dockPadding, R.string.settings_dock_padding),
                    toggle(Keys.Categories.tintCustomIcons, R.string.settings_tint_custom_icons, R.string.settings_tint_custom_icons_desc),
                )
            },
        )

        PageIds.CARDS -> Page(
            id, settings, text(R.string.settings_cards), leftPane = LeftPane.LivePreview, icon = "crop_16_9",
            rows = {
                val ratios = listOf(R.string.settings_ratio_16_9, R.string.settings_ratio_1_1)
                val ratioValues = listOf(CardRatio.R16_9.ordinal, CardRatio.R1_1.ordinal)
                listOf(
                    toggle(Keys.Cards.showTitle, R.string.settings_show_title),
                    slider(Keys.Cards.roundedCorners, R.string.settings_rounded_corners),
                    slider(Keys.Cards.size, R.string.settings_cards_size, centred = true),
                    slider(Keys.Cards.spacing, R.string.settings_cards_spacing),
                    slider(Keys.Cards.loopLimit, R.string.settings_loop_limit),
                    toggle(Keys.Cards.ripple, R.string.settings_ripple),
                    color(Keys.Cards.background, R.string.settings_background_color),
                    nav("focused_card", R.string.settings_focused_card, PageIds.FOCUSED_CARD, "center_focus_strong"),
                    nav("program_cards", R.string.settings_program_cards, PageIds.PROGRAM_CARDS, "slideshow"),
                    choice(env, Keys.Cards.tvRatio, R.string.settings_tv_ratio, ratios, values = ratioValues),
                    choice(env, Keys.Cards.mobileRatio, R.string.settings_mobile_ratio, ratios, values = ratioValues),
                    IconPackRow.row(env),
                    color(Keys.Cards.iconTint, R.string.settings_custom_icon_tint, R.string.settings_tint_custom_icons_desc, enabled = { c -> c.settings[Keys.Cards.iconPack].isNotEmpty() }),
                    nav("icon_set", R.string.iconset_title, PageIds.ICON_SET, "collections"),
                )
            },
        )

        PageIds.FOCUSED_CARD -> Page(
            id, settings, text(R.string.settings_focused_card), leftPane = LeftPane.LivePreview, icon = "center_focus_strong",
            rows = {
                val glow: (RowContext) -> Boolean = { c -> c.settings[Keys.Focus.effect] == 2 }
                val custom: (RowContext) -> Boolean = { c -> c.settings[Keys.Focus.colorOverride] == 2 }
                val pie: (RowContext) -> Boolean = { c -> c.env.sdk >= 28 }
                listOf(
                    Row.Slider(choiceKey = Keys.Focus.scale, title = text(R.string.settings_focused_size), choiceMin = 1),
                    choice(env, Keys.Focus.effect, R.string.settings_focused_effect, listOf(R.string.settings_effect_none, R.string.settings_effect_shadow, R.string.settings_effect_glow)),
                    choice(
                        env, Keys.Focus.animation, R.string.settings_glow_animation,
                        listOf(R.string.settings_anim_none, R.string.settings_anim_pulse, R.string.settings_anim_breath, R.string.settings_anim_fade, R.string.settings_anim_shine),
                        enabled = glow, visible = pie,
                    ),
                    slider(Keys.Focus.glowSize, R.string.settings_glow_size, enabled = glow, visible = pie),
                    color(Keys.Focus.glowColor, R.string.settings_glow_color, R.string.settings_glow_color_desc, enabled = glow, visible = pie),
                    choice(env, Keys.Focus.colorOverride, R.string.settings_colors_override, listOf(R.string.settings_override_none, R.string.settings_override_swap, R.string.settings_override_custom)),
                    color(Keys.Focus.background, R.string.settings_background_color, enabled = custom),
                    color(Keys.Focus.iconTint, R.string.settings_custom_icon_tint, enabled = custom),
                )
            },
        )

        PageIds.PROGRAM_CARDS -> Page(
            id, settings, text(R.string.settings_program_cards), leftPane = LeftPane.LivePreview, icon = "slideshow",
            rows = {
                listOf(
                    choice(env, Keys.Program.playPreview, R.string.settings_play_preview, listOf(R.string.settings_preview_never, R.string.settings_preview_always, R.string.settings_preview_no_media)),
                    toggle(Keys.Program.showProgress, R.string.settings_show_progress),
                    toggle(Keys.Program.showBadge, R.string.settings_show_badge),
                    toggle(Keys.Program.zoomOnFocus, R.string.settings_zoom_focus),
                )
            },
        )

        PageIds.WALLPAPER -> WallpaperPage.page(env)

        PageIds.PICTURE_ADJUSTMENT -> Page(
            id, settings, text(R.string.settings_picture_adjustment), leftPane = LeftPane.Wallpaper, icon = "tune",
            rows = {
                listOf(
                    slider(Keys.Wallpaper.hue, R.string.settings_hue),
                    slider(Keys.Wallpaper.saturation, R.string.settings_saturation),
                    slider(Keys.Wallpaper.contrast, R.string.settings_contrast),
                    slider(Keys.Wallpaper.brightness, R.string.settings_brightness),
                    color(Keys.Wallpaper.tint, R.string.settings_tint),
                    slider(Keys.Wallpaper.tintAmount, R.string.settings_tint_amount),
                    slider(Keys.Wallpaper.blur, R.string.settings_blur),
                    slider(Keys.Wallpaper.shadowHLength, R.string.settings_shadow_h),
                    slider(Keys.Wallpaper.shadowVLength, R.string.settings_shadow_v),
                    slider(Keys.Wallpaper.shadowOpacity, R.string.settings_shadow_opacity),
                    color(Keys.Wallpaper.shadowTint, R.string.settings_shadow_tint),
                )
            },
        )

        PageIds.VIDEO_WALLPAPER -> Page(
            id, settings, text(R.string.settings_video_wallpaper), text(R.string.settings_wallpaper_desc), icon = "videocam",
            rows = {
                listOf(
                    toggle(Keys.Wallpaper.ignoreUpdateOnVideo, R.string.settings_ignore_update, R.string.settings_ignore_update_desc),
                    Row.Slider(floatKey = Keys.Wallpaper.videoColorFrequency, title = text(R.string.settings_video_color_freq), format = ValueFormat.Plain),
                    slider(Keys.Wallpaper.videoVolume, R.string.settings_volume),
                    toggle(Keys.Wallpaper.videoTunneling, R.string.settings_tunneling),
                    toggle(Keys.Wallpaper.videoDecoderFallback, R.string.settings_decoder_fallback),
                )
            },
        )

        PageIds.STATUS_BAR -> Page(
            id, settings, text(R.string.settings_status_bar), leftPane = LeftPane.LivePreview, icon = "horizontal_rule",
            rows = {
                listOf(
                    Row.Action(
                        "notification_permission", text(R.string.settings_notification_permission), { env.actions.openNotificationAccess() },
                        description = { c -> text(if (c.env.notificationAccess) R.string.settings_granted else R.string.settings_not_granted) },
                        icon = "notifications",
                    ),
                    nav("clock", R.string.settings_clock, PageIds.CLOCK, "schedule"),
                    nav("icons", R.string.settings_icons, PageIds.ICONS, "more_horiz"),
                    toggle(Keys.StatusBar.showSystemBar, R.string.settings_show_system_bar),
                    toggle(Keys.StatusBar.autohide, R.string.settings_autohide_status),
                )
            },
        )

        PageIds.CLOCK -> Page(
            id, settings, text(R.string.settings_clock), leftPane = LeftPane.LivePreview, icon = "schedule",
            rows = {
                listOf(
                    toggle(Keys.Clock.show, R.string.settings_show_clock),
                    slider(Keys.Clock.size, R.string.settings_clock_size),
                    color(Keys.Clock.color, R.string.settings_clock_color),
                    color(Keys.Clock.shadow, R.string.settings_drop_shadow),
                    textEntry(Keys.Clock.formatLine1, R.string.settings_clock_line1, R.string.settings_clock_default_format),
                    textEntry(Keys.Clock.formatLine2, R.string.settings_clock_line2, R.string.settings_empty),
                    Row.Footer("clock_help", { ClockHelp.content() }),
                )
            },
        )

        PageIds.ICONS -> Page(
            id, settings, text(R.string.settings_icons), leftPane = LeftPane.LivePreview, icon = "more_horiz",
            rows = {
                listOf(
                    slider(Keys.StatusBar.iconSize, R.string.settings_icons_size, centred = true),
                    color(Keys.StatusBar.iconColor, R.string.settings_icons_color),
                    color(Keys.StatusBar.iconBackground, R.string.settings_icons_container),
                    toggle(Keys.StatusBar.showSettings, R.string.settings_show_settings, R.string.settings_show_settings_desc),
                    toggle(Keys.StatusBar.showBattery, R.string.settings_show_battery, visible = { c -> c.env.hasBattery }),
                    toggle(Keys.StatusBar.showConnectivity, R.string.settings_show_connectivity),
                    toggle(Keys.StatusBar.showNotifications, R.string.settings_show_notifications),
                    toggle(Keys.StatusBar.autohideNotifications, R.string.settings_autohide_notifications, enabled = { c -> c.settings[Keys.StatusBar.showNotifications] }),
                )
            },
        )

        PageIds.LEFT_HEADERS -> Page(
            id, settings, text(R.string.settings_left_headers), leftPane = LeftPane.LivePreview, icon = "vertical_split",
            rows = {
                listOf(
                    toggle(Keys.LeftHeaders.enabled, R.string.settings_enable),
                    toggle(Keys.LeftHeaders.showOnLaunch, R.string.settings_headers_on_launch),
                    toggle(Keys.LeftHeaders.showIcon, R.string.settings_show_icon),
                    toggle(Keys.LeftHeaders.showTitle, R.string.settings_show_title),
                    toggle(Keys.LeftHeaders.showReorder, R.string.settings_show_reorder),
                    toggle(Keys.LeftHeaders.showSettings, R.string.settings_show_settings),
                )
            },
        )

        PageIds.IDLE -> Page(
            id, settings, text(R.string.settings_idle_mode), leftPane = LeftPane.LivePreview, icon = "hourglass_full",
            rows = {
                listOf(
                    slider(Keys.Idle.timeout, R.string.settings_idle_timeout, ValueFormat.OneDecimal),
                    slider(Keys.Idle.opacity, R.string.settings_idle_opacity),
                    toggle(Keys.Idle.keepClock, R.string.settings_keep_clock),
                    toggle(Keys.Idle.preventScreensaver, R.string.settings_prevent_screensaver),
                )
            },
        )

        PageIds.FONT -> Page(
            id, settings, text(R.string.settings_font), text(R.string.settings_font_desc), icon = "font_download",
            leftPane = LeftPane.Qr("https://fonts.google.com/"),
            rows = {
                listOf(
                    FontRows.list(env),
                    textEntry(Keys.Font.name, R.string.settings_font_google),
                    toggle(Keys.Font.bold, R.string.settings_bold),
                    toggle(Keys.Font.italic, R.string.settings_italic),
                    slider(Keys.Font.scale, R.string.settings_size),
                )
            },
        )

        PageIds.POWER -> Page(
            id, settings, text(R.string.settings_power), text(R.string.settings_launcher_related), icon = "settings",
            rows = {
                val idle: (RowContext) -> Boolean = { c -> c.settings[Keys.Power.idleDetection] }
                listOf(
                    AutostartRow.row(env),
                    toggle(Keys.Power.autostartOnResume, R.string.settings_autostart_resume),
                    slider(Keys.Power.autostartDelay, R.string.settings_autostart_delay),
                    toggle(Keys.Power.hdmiExitDetection, R.string.settings_hdmi_exit, visible = { c -> c.env.hasTvInputs }),
                    toggle(Keys.Power.simulateUserActivity, R.string.settings_simulate_activity),
                    toggle(Keys.Power.idleDetection, R.string.settings_idle_detection),
                    time(Keys.Power.idleTimeout, R.string.settings_idle_timeout_power, timeOfDay = false, enabled = idle),
                    // *Screen off* is the accessibility lock-screen action, which exists from Android 9.
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        choice(
                            env, Keys.Power.idleAction, R.string.settings_idle_action,
                            listOf(R.string.settings_idle_action_shutdown, R.string.settings_idle_action_screen_off, R.string.settings_idle_action_screensaver),
                            enabled = idle,
                        )
                    } else {
                        choice(
                            env, Keys.Power.idleAction, R.string.settings_idle_action,
                            listOf(R.string.settings_idle_action_shutdown, R.string.settings_idle_action_screensaver),
                            enabled = idle,
                            values = listOf(0, 2),
                        ).copy(
                            // A *Screen off* carried over from a newer device runs the screensaver here.
                            selected = { c -> listOf(0, 2).indexOf(c.settings[Keys.Power.idleAction].let { if (it == 1) 2 else it }) },
                        )
                    },
                    toggle(
                        Keys.Power.idleExcludesMedia, R.string.settings_idle_media,
                        enabled = { c -> c.settings[Keys.Power.idleDetection] && c.env.notificationAccess },
                        description = { c -> if (c.env.notificationAccess) null else text(R.string.settings_missing_notification_permission) },
                    ),
                    choice(
                        env, Keys.Power.poweroffMode, R.string.settings_shutdown_action,
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            listOf(R.string.settings_shutdown_dialog, R.string.settings_shutdown_dialog_twice, R.string.settings_shutdown_dialog_ok)
                        } else {
                            listOf(R.string.settings_shutdown_dialog, R.string.settings_shutdown_dialog_twice)
                        },
                        enabled = { c -> c.settings[Keys.Power.idleDetection] || c.settings[Keys.Power.hdmiExitDetection] },
                    ),
                )
            },
        )

        PageIds.PARENTAL -> Page(
            id, settings, text(R.string.settings_parental), text(R.string.settings_parental_desc), icon = "supervisor_account",
            rows = {
                val partial: (RowContext) -> Boolean = { c -> c.settings[Keys.Parental.partial] }
                listOf(
                    Row.Action(
                        "parental_code", text(R.string.settings_parental_set_code), { env.actions.setParentalCode() },
                        description = { c -> if (c.settings[Keys.Parental.codeHash].isNotEmpty()) text(R.string.settings_parental_code_set) else null },
                        icon = "lock",
                    ),
                    toggle(Keys.Parental.longDuration, R.string.settings_parental_long),
                    toggle(Keys.Parental.protectSettings, R.string.settings_parental_protect, R.string.settings_parental_protect_desc),
                    toggle(Keys.Parental.partial, R.string.settings_parental_partial),
                    time(Keys.Parental.partialStart, R.string.settings_parental_start, timeOfDay = true, enabled = partial),
                    time(Keys.Parental.partialEnd, R.string.settings_parental_end, timeOfDay = true, enabled = partial),
                )
            },
        )

        PageIds.REMOTE -> Page(
            id, settings, text(R.string.settings_remote), text(R.string.settings_launcher_related), icon = "settings_remote",
            rows = {
                listOf(
                    toggle(Keys.Remote.menuDoublePress, R.string.settings_menu_double),
                    toggle(Keys.Remote.backDoubleAmbient, R.string.settings_back_double, R.string.settings_back_double_desc),
                )
            },
        )

        PageIds.ACCESSIBILITY -> Page(
            id, text(R.string.settings_breadcrumb_system), text(R.string.settings_accessibility), text(R.string.settings_a11y_desc), icon = "accessibility_new",
            rows = {
                listOf(
                    Row.Info("a11y_enabled", text(R.string.settings_a11y_enabled), { c -> text(if (c.env.accessibilityEnabled) R.string.settings_yes else R.string.settings_no) }, icon = "thumb_up"),
                    Row.Info("a11y_started", text(R.string.settings_a11y_started), { c -> text(if (AccessibilityStatus.started()) R.string.settings_yes else R.string.settings_no) }, icon = "thumb_up"),
                    Row.Nav("a11y_open", text(R.string.settings_a11y_open), PageIds.ACCESSIBILITY_CONSENT, "accessibility_new"),
                    toggle(Keys.Service.alternateConfig, R.string.settings_a11y_alternate, R.string.settings_a11y_alternate_desc),
                    toggle(Keys.Service.volumeFix, R.string.settings_a11y_volume_fix, R.string.settings_a11y_volume_fix_desc),
                    toggle(Keys.Service.buttonRemapping, R.string.settings_a11y_remap, R.string.settings_a11y_remap_desc),
                    toggle(Keys.Service.reminder, R.string.settings_a11y_reminder, R.string.settings_a11y_reminder_desc),
                )
            },
        )

        PageIds.ACCESSIBILITY_CONSENT -> Page(
            id, text(R.string.settings_breadcrumb_system), text(R.string.settings_a11y_consent_title), text(R.string.settings_a11y_consent), icon = "accessibility_new",
            rows = {
                listOf(
                    Row.Action("consent_yes", text(R.string.settings_yes), { env.actions.openAccessibilitySettings() }),
                    Row.Action("consent_no", text(R.string.settings_no), { env.navigate(NAV_BACK) }),
                )
            },
        )

        PageIds.THEMES -> Page(
            id, settings, text(R.string.settings_themes), icon = "format_paint",
            rows = {
                listOf(
                    Row.Expander(
                        "theme_import", text(R.string.settings_import),
                        listOf(
                            Row.Action("theme_import_file", text(R.string.settings_import_file), { env.actions.importFromFile(theme = true) }),
                        ),
                        icon = "file_download",
                    ),
                    Row.Expander(
                        "theme_export", text(R.string.settings_export),
                        listOf(
                            Row.Action("theme_export_local", text(R.string.settings_export_local), { env.actions.export(theme = true, share = false) }),
                            Row.Action("theme_export_share", text(R.string.settings_export_share), { env.actions.export(theme = true, share = true) }),
                        ),
                        icon = "file_upload",
                    ),
                )
            },
        )

        PageIds.MANAGE -> Page(
            id, settings, text(R.string.settings_manage), text(R.string.settings_manage_desc), icon = "settings_backup_restore",
            rows = {
                listOf(
                    Row.Expander(
                        "import", text(R.string.settings_import),
                        listOf(
                            Row.Action("import_file", text(R.string.settings_import_file), { env.actions.importFromFile(theme = false) }),
                            Row.Action("import_auto", text(R.string.settings_import_auto), { env.actions.importFromAutoBackup() }),
                        ),
                        icon = "file_download",
                    ),
                    Row.Expander(
                        "export", text(R.string.settings_export),
                        listOf(
                            Row.Action("export_local", text(R.string.settings_export_local), { env.actions.export(theme = false, share = false) }),
                            Row.Action("export_share", text(R.string.settings_export_share), { env.actions.export(theme = false, share = true) }),
                        ),
                        icon = "file_upload",
                    ),
                    Row.Expander(
                        "reset", text(R.string.settings_reset),
                        listOf(
                            Row.Action("reset_cancel", text(R.string.settings_cancel), { }),
                            Row.Action("reset_ok", text(R.string.settings_ok), { env.actions.resetAll() }),
                        ),
                        icon = "settings_backup_restore",
                        initialChild = 0,
                    ),
                )
            },
        )

        else -> null
    }

    /** A navigation target meaning "go back one page". */
    const val NAV_BACK = "<back>"
}

/** Locales with string files, each named in its own language. Empty tag = follow the system. */
object Languages {
    val all: List<Pair<String, String>> = listOf("" to "", "en" to "English", "fi" to "Suomi")
}

/** The accessibility service's "started" state, provided by the system subsystem. */
object AccessibilityStatus {
    @Volatile
    var provider: () -> Boolean = { false }

    fun started(): Boolean = provider()
}
