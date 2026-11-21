/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings

import org.cygnusx1.qlauncher.settings.Badge.AccessibilityRecommended as Arec
import org.cygnusx1.qlauncher.settings.Badge.AccessibilityRequired as Areq
import org.cygnusx1.qlauncher.settings.Badge.Experimental as Xp

/**
 * Every stored setting, with Projectivy's defaults, ranges and steps.
 *
 * Per-category and per-app values are not here: they live in the category and app stores.
 */
@Suppress("MemberVisibilityCanBePrivate")
object Keys {
    private val all = mutableListOf<SettingKey<*>>()

    private fun <K : SettingKey<*>> K.reg(): K = also { all += it }

    /** Every registered key, in declaration order. */
    val registry: List<SettingKey<*>> by lazy {
        // Touch every group so their keys register.
        listOf(Internal, General, Parental, Power, Remote, Service, Look, Font, Wallpaper, Cards, Focus, Program, Categories, Idle, LeftHeaders, StatusBar, Clock)
        all.toList()
    }

    val byName: Map<String, SettingKey<*>> by lazy { registry.associateBy { it.name } }
    val byProjectivyKey: Map<String, SettingKey<*>> by lazy {
        registry.filter { it.projectivyKey != null }.associateBy { it.projectivyKey!! }
    }

    object Internal {
        /** QLauncher's own settings version (Projectivy's `key_app_version` is its app version, not this). */
        val settingsVersion = IntKey("internal.settings_version", 0, exported = true).reg()
        val onboardingCompleted = BoolKey("internal.onboarding_completed", false, "general_onboarding_completed", exported = false).reg()
        val defaultLauncherAsked = BoolKey("internal.default_launcher_asked", false, "general_default_launcher_asked", exported = false).reg()
        val lastStartDate = LongKey("internal.last_start_date", 0L, "general_last_start_date_nexp", exported = false).reg()
        val uniqueId = StringKey("internal.unique_id", "", "general_unique_key_nexp", exported = false).reg()
        val themeAuthor = StringKey("internal.theme_author", "", "general_theme_author_nexp", exported = false).reg()
        val themeName = StringKey("internal.theme_name", "", "general_theme_name_nexp", exported = false).reg()
        val lastInput = StringKey("internal.last_input", "", "input_last_used", exported = false).reg()

        /** When the update feed was last read (epoch ms). */
        val lastUpdateCheck = LongKey("internal.last_update_check", 0L, exported = false).reg()

        /** The newest version the launcher has already announced, so it is announced once. */
        val updateAnnounced = StringKey("internal.update_announced", "", exported = false).reg()

        /** The last text typed into *Install 3rd party apps › Custom URL / Downloader code*. */
        val installInput = StringKey("internal.install_input", "", exported = false, icon = "link").reg()

        /** The serialised last wallpaper, shown at once on the next start. */
        val lastWallpaper = StringKey("internal.last_wallpaper", "", "appearance_wallpaper_last_cached_nexp", exported = false).reg()

        /** Id of the category focused at start and on Home (`-1` = the first visible one). */
        val defaultCategoryId = IntKey("internal.default_category_id", -1, projectivyKey = "appearance_categories_default_category_id", theme = true, icon = "category").reg()
    }

    object General {
        val language = StringKey("general.language", "", icon = "translate").reg()
        val overrideHome = BoolKey("general.override_home", true, "general_override_home", icon = "home", badges = setOf(Areq)).reg()

        /** Unset by default: it then follows whether READ_TV_LISTINGS is granted. */
        val enableChannels = BoolKey("general.enable_channels", false, "general_enable_tv_channels", icon = "live_tv", storeDefault = true).reg()
        val programsPerChannel = IntKey("general.programs_per_channel", 30, 0, 200, 1, "action_channel_general_programs_per_channel", icon = "format_list_numbered").reg()
        val programsPerApp = IntKey("general.programs_per_app", 0, 0, 10, 1, "action_watch_next_programs_per_app", icon = "format_list_numbered").reg()
        /**
         * Anonymous crash and error reports, off until the user turns them on. Projectivy's
         * two reporting switches are not imported: they agreed to a different service.
         */
        val crashErrorReports = BoolKey("general.crash_error_reports", false, icon = "bug_report").reg()
        val checkUpdates = BoolKey("general.check_updates", true, "general_check_updates", icon = "update").reg()
        val useInternalTvView = BoolKey("general.use_internal_tv_view", false, "input_use_internal_tv", icon = "tv").reg()
    }

    object Parental {
        val longDuration = BoolKey("parental.long_duration", false, "parental_control_code_long_duration", icon = "timer").reg()
        val protectSettings = BoolKey("parental.protect_settings", false, "parental_control_protect_settings", icon = "security").reg()
        val partial = BoolKey("parental.partial", false, "parental_control_partial", icon = "timelapse").reg()

        /** Milliseconds after midnight. */
        val partialStart = LongKey("parental.partial_start", 0L, "parental_control_partial_start", icon = "timer").reg()
        val partialEnd = LongKey("parental.partial_end", 0L, "parental_control_partial_end", icon = "timer_off").reg()

        /** Salted hash and length of the code; never exported. */
        val codeHash = StringKey("parental.code_hash", "", exported = false, icon = "lock").reg()
        val recoveryHash = StringKey("parental.recovery_hash", "", exported = false, icon = "report").reg()
    }

    object Power {
        /** Empty = none; `screensaver`; or `pkg/activity`. */
        val autostartSource = StringKey("power.autostart_source", "", "power_autostart_source", icon = "play_arrow", badges = setOf(Arec)).reg()
        val autostartOnResume = BoolKey("power.autostart_on_resume", false, "power_autostart_leaving_standby", icon = "power_settings_new", badges = setOf(Arec, Xp)).reg()
        val autostartDelay = IntKey("power.autostart_delay", 0, 0, 30, 1, "power_autostart_delay", icon = "hourglass_full").reg()
        val hdmiExitDetection = BoolKey("power.hdmi_exit_detection", false, "power_hdmi_input_exit_detection", icon = "settings_input_hdmi", badges = setOf(Areq, Xp)).reg()
        val simulateUserActivity = BoolKey("power.simulate_user_activity", false, "power_simulate_user_activity", icon = "touch_app", badges = setOf(Areq)).reg()
        val idleDetection = BoolKey("power.idle_detection", false, "power_internal_idle_detection", icon = "snooze", badges = setOf(Areq)).reg()

        /** Milliseconds. */
        val idleTimeout = LongKey("power.idle_timeout", 3_600_000L, "power_internal_idle_timeout", icon = "hourglass_empty", badges = setOf(Areq)).reg()

        /** 0 Shutdown, 1 Screen off, 2 Screensaver. */
        val idleAction = ChoiceKey("power.idle_action", 0, 3, "power_internal_idle_action", icon = "power_settings_new", badges = setOf(Areq)).reg()
        val idleExcludesMedia = BoolKey("power.idle_excludes_media", false, "power_internal_idle_excludes_media_playback", icon = "play_circle_outline", badges = setOf(Areq, Xp)).reg()

        /** 0 Power Dialog, 1 Power Dialog (call twice), 2 Power Dialog + OK (API 33+). */
        val poweroffMode = ChoiceKey("power.poweroff_mode", 0, 3, "power_poweroff_mode", icon = "power_settings_new", badges = setOf(Areq)).reg()
    }

    object Remote {
        val menuDoublePress = BoolKey("remote.menu_double_press", true, "remotecontrol_show_menu", icon = "info", badges = setOf(Areq)).reg()
        val backDoubleAmbient = BoolKey("remote.back_double_ambient", true, "remotecontrol_double_back_ambient_mode", icon = "movie_filter", badges = setOf(Areq)).reg()
    }

    object Service {
        val reminder = BoolKey("service.reminder", true, "accessibility_service_enable_reminder", icon = "notification_important").reg()
        val alternateConfig = BoolKey("service.alternate_config", false, "accessibility_alternate_config", icon = "tune", badges = setOf(Areq)).reg()
        val buttonRemapping = BoolKey("service.button_remapping", true, "accessibility_button_remapping", icon = "gamepad", badges = setOf(Areq)).reg()
        val volumeFix = BoolKey("service.volume_fix", true, "accessibility_volume_fix", icon = "volume_up", badges = setOf(Areq)).reg()
    }

    /** Appearance › general. */
    object Look {
        val loadCardsWhileScrolling = BoolKey("appearance.load_cards_while_scrolling", false, "appearance_general_load_cards_while_scrolling", theme = true, icon = "touch_app").reg()
        /**
         * Default: the picker's third row, second swatch (hue 204°, #0099FF) at luminance 0,
         * opaque, with *Prefer dynamic color* off (lowest bit clear).
         */
        val accent = ColorKey("appearance.accent", 0xFF0099FE.toInt(), projectivyKey = "appearance_general_tint", theme = true).reg()
    }

    object Font {
        /** `default`, a system family name, or a Google Fonts family. */
        val name = StringKey("font.name", "default", "appearance_font_name", theme = true, icon = "font_download").reg()
        val bold = BoolKey("font.bold", false, "appearance_font_bold", theme = true, icon = "format_bold").reg()
        val italic = BoolKey("font.italic", false, "appearance_font_italic", theme = true, icon = "format_italic").reg()
        val scale = FloatKey("font.scale", 1.0f, 0.7f, 2.0f, 0.1f, "appearance_font_scale", theme = true, icon = "format_size").reg()
    }

    object Wallpaper {
        /** Provider id (the same UUIDs as Projectivy's built-in providers, so imports map). */
        val provider = StringKey("wallpaper.provider", WallpaperProviders.WIKIMEDIA, "appearance_wallpaper_provider", theme = true, icon = "image_search").reg()
        val providerPackage = StringKey("wallpaper.provider_package", "", "appearance_wallpaper_provider_packagename", theme = true).reg()

        /** Seconds; 0 = never change automatically. */
        val updateDelay = IntKey("wallpaper.update_delay", 60, 0, 3600, 15, "appearance_wallpaper_update_delay", theme = true, icon = "access_time").reg()

        /** 0 Automatic, 1 Crop, 2 Stretch, 3 Fit center, 4 Fit center + blurred background, 5 Blur. */
        val displayMode = ChoiceKey("wallpaper.display_mode", 0, 6, "appearance_wallpaper_display_mode", theme = true, icon = "photo").reg()

        /** 0 None, 1 Random, 2 Fade, 3 Zoom, 4 Horizontal Slide, 5 Vertical Slide. */
        val transition = ChoiceKey("wallpaper.transition", 3, 6, "appearance_wallpaper_transition", theme = true, icon = "view_carousel").reg()

        /** As [displayMode] with 0 = Skip. */
        val wrongFormatMode = ChoiceKey("wallpaper.wrong_format_mode", 0, 6, "appearance_wallpaper_wrong_format_display_mode", theme = true, icon = "broken_image").reg()
        val minAcceptedWidth = IntKey("wallpaper.min_accepted_width", 800, 0, 4000, 100, "appearance_wallpaper_min_accepted_width", theme = true, icon = "straighten").reg()
        val ignoreUpdateOnVideo = BoolKey("wallpaper.ignore_update_on_video", true, "appearance_wallpaper_ignore_update_on_video_playing", theme = true, icon = "timer_off").reg()

        /** Seconds between video frames sampled for colours; default 5 on NVIDIA, else 0. */
        val videoColorFrequency = FloatKey("wallpaper.video_color_frequency", if (isNvidia()) 5f else 0f, 0f, 60f, 1f, "appearance_wallpaper_video_dynamic_color_update_frequency_nexp", exported = false, icon = "palette").reg()
        val videoVolume = FloatKey("wallpaper.video_volume", 0f, 0f, 1f, 0.05f, "appearance_wallpaper_video_volume", theme = true, icon = "volume_up").reg()
        val videoTunneling = BoolKey("wallpaper.video_tunneling", true, "appearance_wallpaper_video_tunneling", theme = true, icon = "speed", badges = setOf(Xp)).reg()
        val videoDecoderFallback = BoolKey("wallpaper.video_decoder_fallback", false, "appearance_wallpaper_video_decoders_fallback", theme = true, icon = "alt_route").reg()
        val brightness = IntKey("wallpaper.brightness", 0, -255, 255, 5, "appearance_wallpaper_adjust_brightness", theme = true, icon = "brightness_6").reg()
        val contrast = IntKey("wallpaper.contrast", 0, -100, 100, 2, "appearance_wallpaper_adjust_contrast", theme = true, icon = "tonality").reg()
        val saturation = IntKey("wallpaper.saturation", 0, -100, 100, 2, "appearance_wallpaper_adjust_saturation", theme = true, icon = "gradient").reg()
        val hue = IntKey("wallpaper.hue", 0, -180, 180, 2, "appearance_wallpaper_adjust_hue", theme = true, icon = "color_lens").reg()
        val blur = IntKey("wallpaper.blur", 0, 0, 16, 1, "appearance_wallpaper_adjust_blur", theme = true, icon = "blur_on").reg()
        val tint = ColorKey("wallpaper.tint", projectivyKey = "appearance_wallpaper_adjust_tint", theme = true, icon = "colorize").reg()
        val tintAmount = IntKey("wallpaper.tint_amount", 0, 0, 100, 1, "appearance_wallpaper_adjust_tint_amount", theme = true, icon = "highlight").reg()
        val shadowHLength = FloatKey("wallpaper.shadow_h_length", 0.2f, -1f, 1f, 0.1f, "appearance_wallpaper_adjust_shadow_overlay_hlength", theme = true, icon = "vertical_distribute").reg()
        val shadowVLength = FloatKey("wallpaper.shadow_v_length", 0.1f, -1f, 1f, 0.1f, "appearance_wallpaper_adjust_shadow_overlay_vlength", theme = true, icon = "horizontal_distribute").reg()
        val shadowOpacity = FloatKey("wallpaper.shadow_opacity", 0.3f, 0f, 1f, 0.1f, "appearance_wallpaper_adjust_shadow_overlay_opacity", theme = true, icon = "opacity").reg()
        val shadowTint = ColorKey("wallpaper.shadow_tint", projectivyKey = "appearance_wallpaper_adjust_shadow_overlay_tint", theme = true).reg()
    }

    object Cards {
        val showTitle = BoolKey("cards.show_title", true, "appearance_cards_show_title", theme = true, icon = "title").reg()
        val roundedCorners = FloatKey("cards.rounded_corners", 0.3f, 0f, 1f, 0.1f, "appearance_cards_rounded_corners", theme = true, icon = "rounded_corner").reg()
        val size = FloatKey("cards.size", 1.0f, 0.5f, 1.5f, 0.1f, "appearance_cards_size", theme = true, icon = "photo_size_select_large").reg()
        val spacing = IntKey("cards.spacing", 8, 1, 16, 1, "appearance_cards_spacing", theme = true, icon = "zoom_out_map").reg()
        val loopLimit = IntKey("cards.loop_limit", 0, 0, 10, 1, "appearance_cards_loop_limit", theme = true, icon = "loop").reg()
        val ripple = BoolKey("cards.ripple", true, "appearance_cards_ripple_effect", theme = true, icon = "touch_app").reg()
        val background = ColorKey("cards.background", projectivyKey = "appearance_cards_bg_tint", theme = true).reg()
        val iconTint = ColorKey("cards.icon_tint", projectivyKey = "appearance_cards_tint", theme = true).reg()

        /** Index into [CardRatio]; only 16:9 (0) and 1:1 (3) are offered. */
        val tvRatio = ChoiceKey("cards.tv_ratio", CardRatio.R16_9.ordinal, CardRatio.entries.size, "appearance_cards_tv_app_aspect_ratio", theme = true, icon = "aspect_ratio").reg()
        val mobileRatio = ChoiceKey("cards.mobile_ratio", CardRatio.R1_1.ordinal, CardRatio.entries.size, "appearance_cards_mobile_app_aspect_ratio", theme = true, icon = "aspect_ratio").reg()
        val iconPack = StringKey("cards.icon_pack", "", "appearance_cards_icon_pack", theme = true, icon = "apps").reg()
    }

    object Focus {
        /** 0 None (code only), 1 XS, 2 S, 3 M, 4 L; the slider shows XS..L as 1..4. */
        val scale = ChoiceKey("focus.scale", 3, 5, "appearance_cards_focused_scale", theme = true, icon = "photo_size_select_large").reg()

        /** 0 None, 1 Drop shadow, 2 Glow. */
        val effect = ChoiceKey("focus.effect", 2, 3, "appearance_cards_focused_effect", theme = true, icon = "brightness_6").reg()

        /** 0 None, 1 Pulse, 2 Breath, 3 Fade, 4 Shine. */
        val animation = ChoiceKey("focus.animation", 1, 5, "appearance_cards_focused_animation", theme = true, icon = "play_arrow").reg()
        val glowSize = IntKey("focus.glow_size", 32, 5, 32, 1, "appearance_cards_focused_glow_size", theme = true, icon = "format_size").reg()

        /** 0 = empty: use the card's own colour. */
        val glowColor = ColorKey("focus.glow_color", 0, "appearance_cards_focused_glow_tint", theme = true).reg()

        /** 0 None, 1 Swap background/tint, 2 Custom. */
        val colorOverride = ChoiceKey("focus.color_override", 0, 3, "appearance_cards_focused_color_override_mode", theme = true, icon = "palette").reg()
        val background = ColorKey("focus.background", projectivyKey = "appearance_cards_focused_bg_tint", theme = true).reg()
        val iconTint = ColorKey("focus.icon_tint", projectivyKey = "appearance_cards_focused_tint", theme = true).reg()
    }

    object Program {
        /** 0 Never, 1 Always, 2 When no background media is playing. */
        val playPreview = ChoiceKey("program.play_preview", 1, 3, "appearance_cards_program_play_preview", theme = true, icon = "play_arrow").reg()
        val showProgress = BoolKey("program.show_progress", true, "appearance_cards_program_show_progressbar", theme = true, icon = "timelapse").reg()
        val showBadge = BoolKey("program.show_badge", true, "appearance_cards_program_show_badge", theme = true, icon = "loyalty").reg()
        val zoomOnFocus = BoolKey("program.zoom_on_focus", true, "appearance_cards_program_zoom_on_focus", theme = true, icon = "zoom_in").reg()
    }

    object Categories {
        val showTitle = BoolKey("categories.show_title", true, "appearance_categories_show_title", theme = true, icon = "title").reg()
        val showIcon = BoolKey("categories.show_icon", true, "appearance_categories_show_icon", theme = true, icon = "image").reg()
        val rowsSpacing = FloatKey("categories.rows_spacing", 0f, 0f, 1f, 0.01f, "appearance_categories_rows_spacing", theme = true, icon = "format_line_spacing").reg()
        val fixedPosition = BoolKey("categories.fixed_position", true, "appearance_categories_focused_category_position_fixed", theme = true, icon = "vertical_align_center").reg()
        val hideRowsAbove = BoolKey("categories.hide_rows_above", false, "appearance_categories_hide_rows_above_selection", theme = true, icon = "visibility_off").reg()
        val rememberFocus = BoolKey("categories.remember_focus", true, "appearance_categories_remember_focused_card_position", theme = true, icon = "push_pin").reg()
        val unselectedOpacity = FloatKey("categories.unselected_opacity", 0.3f, 0f, 1f, 0.01f, "appearance_categories_unselected_row_opacity", theme = true, icon = "opacity").reg()
        val topMargin = FloatKey("categories.top_margin", 0.5f, 0.05f, 0.95f, 0.01f, "appearance_categories_listing_top_margin", theme = true, icon = "vertical_align_bottom").reg()
        val startMargin = FloatKey("categories.start_margin", 0.05f, 0f, 1f, 0.01f, "appearance_categories_start_margin", theme = true, icon = "arrow_left").reg()
        val endMargin = FloatKey("categories.end_margin", 0.95f, 0f, 1f, 0.01f, "appearance_categories_end_margin", theme = true, icon = "arrow_right").reg()
        val dockPadding = IntKey("categories.dock_padding", 6, 0, 16, 2, "appearance_categories_dock_padding", theme = true, icon = "zoom_out_map").reg()
        val tintCustomIcons = BoolKey("categories.tint_custom_icons", true, "appearance_categories_tint_custom_icons", theme = true, icon = "palette").reg()
    }

    object Idle {
        /** Minutes. */
        val timeout = FloatKey("idle.timeout", 2.0f, 0f, 5f, 0.5f, "appearance_idle_timeout_duration", theme = true, icon = "hourglass_full").reg()
        val opacity = FloatKey("idle.opacity", 0f, 0f, 1f, 0.1f, "appearance_idle_opacity", theme = true, icon = "opacity").reg()
        val keepClock = BoolKey("idle.keep_clock", true, "appearance_idle_no_clock_fade", theme = true, icon = "access_time").reg()
        val preventScreensaver = BoolKey("idle.prevent_screensaver", false, "appearance_idle_prevent_screensaver", theme = true, icon = "ondemand_video").reg()
    }

    object LeftHeaders {
        val enabled = BoolKey("left_headers.enabled", false, "appearance_left_headers_enabled", theme = true, icon = "view_list").reg()
        val showOnLaunch = BoolKey("left_headers.show_on_launch", false, "appearance_left_headers_show_on_launch", theme = true, icon = "launch").reg()
        val showIcon = BoolKey("left_headers.show_icon", true, "appearance_left_headers_show_icon", theme = true, icon = "image").reg()
        val showTitle = BoolKey("left_headers.show_title", false, "appearance_left_headers_show_title", theme = true, icon = "title").reg()
        val showReorder = BoolKey("left_headers.show_reorder", true, "appearance_left_headers_show_reorder", theme = true, icon = "swap_vertical_circle").reg()
        val showSettings = BoolKey("left_headers.show_settings", true, "appearance_left_headers_show_settings", theme = true, icon = "build_circle").reg()
    }

    object StatusBar {
        val autohide = BoolKey("status_bar.autohide", true, "appearance_statusbar_autohide", theme = true, icon = "visibility_off").reg()
        val showSystemBar = BoolKey("status_bar.show_system_bar", false, "appearance_statusbar_show_system_statusbar", theme = true, icon = "remove_from_queue").reg()
        val iconSize = FloatKey("status_bar.icon_size", 1.0f, 0.5f, 1.5f, 0.1f, "appearance_statusbar_maincontainer_icon_size", theme = true, icon = "photo_size_select_large").reg()
        val iconColor = ColorKey("status_bar.icon_color", projectivyKey = "appearance_statusbar_maincontainer_icon_tint", theme = true).reg()
        val iconBackground = ColorKey("status_bar.icon_background", projectivyKey = "appearance_statusbar_maincontainer_icon_background_tint", theme = true).reg()
        val showSettings = BoolKey("status_bar.show_settings", true, "appearance_statusbar_maincontainer_show_settings", theme = true, icon = "settings").reg()
        val showBattery = BoolKey("status_bar.show_battery", true, "appearance_statusbar_maincontainer_show_battery", theme = true, icon = "battery_full").reg()
        val showConnectivity = BoolKey("status_bar.show_connectivity", true, "appearance_statusbar_maincontainer_show_connectivity", theme = true, icon = "wifi").reg()
        val showNotifications = BoolKey("status_bar.show_notifications", true, "appearance_statusbar_maincontainer_show_notifications", theme = true, icon = "notifications_active").reg()
        val autohideNotifications = BoolKey("status_bar.autohide_notifications", true, "appearance_statusbar_maincontainer_autohide_notifications", theme = true, icon = "visibility_off").reg()
    }

    object Clock {
        val show = BoolKey("clock.show", true, "appearance_statusbar_clock_show", theme = true, icon = "access_time").reg()
        val size = IntKey("clock.size", 32, 16, 64, 8, "appearance_statusbar_clock_size", theme = true, icon = "format_size").reg()

        /** Empty = the system's short time format. */
        val formatLine1 = StringKey("clock.format_line1", "", "appearance_statusbar_clock_format_l1", theme = true, icon = "text_fields").reg()
        val formatLine2 = StringKey("clock.format_line2", "", "appearance_statusbar_clock_format_l2", theme = true, icon = "text_fields").reg()
        val color = ColorKey("clock.color", projectivyKey = "appearance_statusbar_clock_tint", theme = true).reg()
        val shadow = ColorKey("clock.shadow", projectivyKey = "appearance_statusbar_clock_drop_shadow_tint", theme = true).reg()
    }
}

private fun isNvidia(): Boolean = runCatching {
    android.os.Build.MANUFACTURER.orEmpty().contains("nvidia", ignoreCase = true)
}.getOrDefault(false)

/** Card aspect ratios, in Projectivy's order. */
enum class CardRatio(val ratio: Float) {
    R16_9(16f / 9f),
    R3_2(3f / 2f),
    R4_3(4f / 3f),
    R1_1(1f),
    R2_3(2f / 3f),
    Poster(1f / 1.441f),
}

/** Built-in wallpaper provider ids. The values are Projectivy's, so settings import one to one. */
object WallpaperProviders {
    const val NONE = "773b5948-b4d1-49c5-b994-999210bc8877"
    const val DEFAULT = "326618d8-3242-4322-a1b1-9e60e5d3dfc3"
    const val DYNAMIC_COLORS = "e77610c3-d495-4090-b387-fae3faafa718"
    const val FOLDER = "0b52e4f1-78c9-4d65-a732-d5f95f4665b7"
    const val MEDIA_FILE = "5ca2f519-b7e3-4314-b878-40c4cc54e64b"
    const val THEME = "78edbc3e-7e0e-49b6-8bf0-3787ee67b1c8"
    const val FOCUSED_PROGRAM = "895c68b3-6522-43b8-85bc-9a1624c41850"
    const val RANDOM_PROGRAM = "eeac320d-bf54-449d-b852-b9048acd7c4b"
    const val NOW_PLAYING = "b7ac18ab-d18d-4587-8ed7-50af45541033"
    const val WIKIMEDIA = "5b0f1c1e-6c3a-4a0e-9d7b-2f6d3c8e7a41"
    const val REDDIT = "eea7a685-d68f-4fb5-b46e-b4897af5413f"
}
