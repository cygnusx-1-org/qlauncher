/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.settings.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.WallpaperProviders
import org.cygnusx1.qlauncher.ui.settings.model.Option
import org.cygnusx1.qlauncher.ui.settings.model.Page
import org.cygnusx1.qlauncher.ui.settings.model.Row
import org.cygnusx1.qlauncher.ui.settings.model.RowContext
import org.cygnusx1.qlauncher.ui.settings.model.ValueFormat
import org.cygnusx1.qlauncher.ui.settings.model.raw
import org.cygnusx1.qlauncher.ui.settings.model.text
import org.cygnusx1.qlauncher.ui.theme.QTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Rows whose options come from other subsystems (installed apps, icon packs, fonts, wallpaper
 * providers). Each holds provider lambdas that the activity sets once the subsystems exist.
 */

/** *Icon pack*: installed packs, or "No icon pack installed", then More…. */
object IconPackRow {
    /** (package, label) of installed icon packs. */
    @Volatile var packs: () -> List<Pair<String, String>> = { emptyList() }

    fun row(env: PageEnv): Row = Row.Dropdown(
        id = Keys.Cards.iconPack.name,
        title = text(R.string.settings_icon_pack),
        options = { _ ->
            val p = packs()
            val head = if (p.isEmpty()) listOf(Option(text(R.string.settings_icon_pack_none))) else listOf(Option(text(R.string.settings_icon_pack_off))) + p.map { Option(raw(it.second)) }
            head + Option(text(R.string.settings_more))
        },
        selected = { c ->
            val p = packs()
            val cur = c.settings[Keys.Cards.iconPack]
            if (cur.isEmpty()) 0 else (p.indexOfFirst { it.first == cur } + 1).coerceAtLeast(0)
        },
        onSelect = { i ->
            val p = packs()
            when {
                i == p.size + 1 || (p.isEmpty() && i == 1) -> env.navigate(PageIds.INSTALL_APPS)
                i == 0 -> env.repo.update(Keys.Cards.iconPack, "")
                else -> env.repo.update(Keys.Cards.iconPack, p[i - 1].first)
            }
        },
        icon = Keys.Cards.iconPack.icon,
    )
}

/** *Autostart on boot*: None, the screensaver, then every installed app. */
object AutostartRow {
    /** (item key, label) of launchable apps. */
    @Volatile var apps: () -> List<Pair<String, String>> = { emptyList() }

    @Volatile var screensaverSupported: () -> Boolean = { true }

    fun row(env: PageEnv): Row = Row.Dropdown(
        id = Keys.Power.autostartSource.name,
        title = text(R.string.settings_autostart),
        options = { _ ->
            buildList {
                add(Option(text(R.string.settings_autostart_none)))
                if (screensaverSupported()) add(Option(text(R.string.settings_autostart_screensaver)))
                apps().forEach { add(Option(raw(it.second))) }
            }
        },
        selected = { c ->
            val cur = c.settings[Keys.Power.autostartSource]
            val ss = screensaverSupported()
            when {
                cur.isEmpty() -> 0
                cur == SCREENSAVER -> if (ss) 1 else 0
                else -> apps().indexOfFirst { it.first == cur }.let { if (it < 0) 0 else it + if (ss) 2 else 1 }
            }
        },
        onSelect = { i ->
            val ss = screensaverSupported()
            val v = when {
                i == 0 -> ""
                ss && i == 1 -> SCREENSAVER
                else -> apps().getOrNull(i - if (ss) 2 else 1)?.first.orEmpty()
            }
            env.repo.update(Keys.Power.autostartSource, v)
        },
        icon = Keys.Power.autostartSource.icon,
        badges = Keys.Power.autostartSource.badges,
    )

    const val SCREENSAVER = "screensaver"
}

/** *Choose from list*: Default, the system families, then Google Fonts (🔗). */
object FontRows {
    val systemFamilies = listOf(
        "casual", "cursive", "monospace", "sans-serif-thin", "sans-serif-light", "sans-serif",
        "sans-serif-medium", "sans-serif-black", "sans-serif-condensed-light", "sans-serif-condensed",
        "sans-serif-smallcaps", "serif", "serif-monospace",
    )

    /** Google Fonts family names. */
    @Volatile var googleFonts: () -> List<String> = { emptyList() }

    fun list(env: PageEnv): Row {
        fun entries(): List<String> = listOf("default") + systemFamilies + googleFonts()
        return Row.Dropdown(
            id = "font.list",
            title = text(R.string.settings_font_list),
            options = { _ ->
                val g = googleFonts().toSet()
                entries().map { name ->
                    when {
                        name == "default" -> Option(text(R.string.settings_font_default))
                        name in g -> Option(raw(name), prefix = "🔗 ")
                        else -> Option(raw(name.replaceFirstChar { it.uppercase() }))
                    }
                }
            },
            selected = { c -> entries().indexOf(c.settings[Keys.Font.name]) },
            onSelect = { i -> env.repo.update(Keys.Font.name, entries()[i]) },
            description = { c ->
                val n = c.settings[Keys.Font.name]
                if (n == "default") text(R.string.settings_font_default) else raw(n)
            },
            icon = "font_download",
        )
    }
}

/** The help block under the clock format rows. */
object ClockHelp {
    /** Formats a pattern with markup into styled text; replaced by the clock-format subsystem. */
    @Volatile var formatter: (String, Date) -> AnnotatedString = { p, d ->
        AnnotatedString(runCatching { SimpleDateFormat(p, Locale.getDefault()).format(d) }.getOrDefault(p))
    }

    private val samples = listOf("d/MM/yy HH:mm", "E d MMM yyyy", "EEEE d MMMM")
    private val markup = listOf("**bold**", "*italic*", "__underline__", "~~strikethrough~~", "x~subscript~", "x^superscript^", "--half size--", "++larger++")

    @Composable
    fun content() {
        val now = Date()
        val grey = Color.White.copy(alpha = 0.5f)
        Column {
            samples.forEach { p ->
                Text(AnnotatedString("$p  →  ") + formatter(p, now), color = grey, style = QTheme.type.rowDescription)
            }
            Spacer(Modifier.height(8.dp))
            markup.forEach { m ->
                Text(AnnotatedString("'$m'  →  ") + formatter("'$m'", now), color = grey, style = QTheme.type.rowDescription)
            }
        }
    }
}

/** The *Wallpaper* page, whose rows depend on the wallpaper subsystem. */
object WallpaperPage {
    /** A wallpaper source as the dropdown lists it. */
    data class Source(val id: String, val label: String, val plugin: Boolean)

    /** Sources in Projectivy's order, then plugins; filled by the wallpaper subsystem. */
    @Volatile var sources: () -> List<Source> = { emptyList() }

    /** Number of wallpapers the current source has loaded, or null. */
    @Volatile var count: () -> Int? = { null }

    /** Chooses a source (may start the Reddit login first). */
    @Volatile var selectSource: (PageEnv, String) -> Unit = { env, id -> env.repo.update(Keys.Wallpaper.provider, id) }

    /** Providers with a Configure page. */
    @Volatile var hasConfigure: (String) -> Boolean = { id ->
        id in setOf(WallpaperProviders.FOLDER, WallpaperProviders.MEDIA_FILE, WallpaperProviders.THEME, WallpaperProviders.REDDIT, WallpaperProviders.WIKIMEDIA)
    }

    fun page(env: PageEnv): Page = Page(
        PageIds.WALLPAPER, text(R.string.settings_breadcrumb), text(R.string.settings_wallpaper), text(R.string.settings_wallpaper_desc), icon = "settings",
        rows = { _ ->
            val notFit: (RowContext) -> Boolean = { c -> c.settings[Keys.Wallpaper.displayMode] !in setOf(3, 4) }
            listOf(
                Row.Dropdown(
                    id = Keys.Wallpaper.provider.name,
                    title = text(R.string.settings_launcher_wallpaper),
                    options = { _ ->
                        sources().map { s -> Option(raw(s.label), prefix = if (s.plugin) "🔌 " else null) } + Option(text(R.string.settings_more))
                    },
                    selected = { c -> sources().indexOfFirst { it.id == c.settings[Keys.Wallpaper.provider] } },
                    onSelect = { i ->
                        val s = sources()
                        if (i == s.size) env.navigate(PageIds.INSTALL_APPS) else selectSource(env, s[i].id)
                    },
                    icon = Keys.Wallpaper.provider.icon,
                ),
                Row.Nav(
                    "configure", text(R.string.settings_configure), PageIds.WALLPAPER_CONFIGURE, "build_circle",
                    enabled = { c -> hasConfigure(c.settings[Keys.Wallpaper.provider]) },
                ),
                Row.Action(
                    "show_wallpapers", text(R.string.settings_show_wallpapers), { env.actions.openWallpaperGallery() },
                    description = { _ -> count()?.let { raw(it.toString()) } },
                    icon = "image", chevron = true,
                ),
                nav("picture_adjustment", R.string.settings_picture_adjustment, PageIds.PICTURE_ADJUSTMENT, "tune"),
                choice(
                    env, Keys.Wallpaper.transition, R.string.settings_image_transition,
                    listOf(R.string.settings_tr_none, R.string.settings_tr_random, R.string.settings_tr_fade, R.string.settings_tr_zoom, R.string.settings_tr_hslide, R.string.settings_tr_vslide),
                ),
                slider(Keys.Wallpaper.updateDelay, R.string.settings_update_frequency, ValueFormat.MinutesSeconds),
                nav("video_wallpaper", R.string.settings_video_wallpaper, PageIds.VIDEO_WALLPAPER, "videocam"),
                choice(
                    env, Keys.Wallpaper.displayMode, R.string.settings_display_mode,
                    listOf(R.string.settings_dm_automatic, R.string.settings_dm_crop, R.string.settings_dm_stretch, R.string.settings_dm_fit, R.string.settings_dm_fit_blur, R.string.settings_dm_blur),
                ),
                choice(
                    env, Keys.Wallpaper.wrongFormatMode, R.string.settings_bad_ratio,
                    listOf(R.string.settings_dm_skip, R.string.settings_dm_crop, R.string.settings_dm_stretch, R.string.settings_dm_fit, R.string.settings_dm_fit_blur, R.string.settings_dm_blur),
                    enabled = notFit,
                ),
                slider(Keys.Wallpaper.minAcceptedWidth, R.string.settings_min_width, enabled = notFit),
            )
        },
    )
}
