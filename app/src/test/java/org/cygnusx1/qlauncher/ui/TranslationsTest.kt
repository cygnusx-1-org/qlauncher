/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui

import org.cygnusx1.qlauncher.ui.settings.pages.Languages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every translation in res/values-<language> against the English base: the same resources in the
 * same file and order, the same format arguments, plural items and line breaks, no English left
 * behind, and each language offered in *Change application language*.
 */
class TranslationsTest {
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private val base = load(File(res, "values"))
    private val translatable = base.filterValues { it.translatable }
    private val locales = res.listFiles { f -> f.isDirectory && LOCALE_DIR.matches(f.name) && strings(f).isNotEmpty() }!!.sortedBy { it.name }

    @Test
    fun translationsExist() {
        assertTrue("no res/values-<language> folder with strings", locales.isNotEmpty())
    }

    @Test
    fun everyLanguageIsInThePicker() {
        val tags = locales.map { dirTag(it.name) }.toSet() + "en"
        assertEquals(tags, Languages.all.map { it.first }.filter { it.isNotEmpty() }.toSet())
    }

    @Test
    fun sameResourcesInTheSameFilesAndOrder() {
        for (dir in locales) {
            val translated = load(dir)
            val missing = translatable.keys - translated.keys
            assertTrue("${dir.name} lacks $missing", missing.isEmpty())
            val extra = translated.keys - translatable.keys
            assertTrue("${dir.name} has resources that are not translatable or not in the base: $extra", extra.isEmpty())
            for ((file, names) in translated.values.groupBy { it.file }) {
                val expected = translatable.values.filter { it.file == file }.map { it.name }
                assertEquals("${dir.name}/$file order", expected, names.map { it.name })
            }
        }
    }

    @Test
    fun formatArgumentsPluralsAndLineBreaksMatch() {
        for (dir in locales) {
            for (t in load(dir).values) {
                val b = translatable[t.name] ?: continue
                val where = "${dir.name}/${t.file} ${t.name}"
                assertEquals("$where kind", b.plural, t.plural)
                if (b.plural) {
                    assertTrue("$where needs an \"other\" item", "other" in t.items)
                    val args = formatArgs(b.items.getValue("other"))
                    for ((quantity, text) in t.items) assertEquals("$where [$quantity] arguments", args, formatArgs(text))
                } else {
                    assertEquals("$where arguments", formatArgs(b.text), formatArgs(t.text))
                    assertEquals("$where line breaks", b.text.split("\\n").size, t.text.split("\\n").size)
                }
            }
        }
    }

    @Test
    fun noEnglishLeftBehind() {
        for (dir in locales) {
            val allowed = SAME_AS_ENGLISH[dirTag(dir.name)].orEmpty()
            val same = load(dir).values.filter { t ->
                val b = translatable[t.name] ?: return@filter false
                t.name !in allowed && (if (t.plural) t.items == b.items else t.text == b.text)
            }.map { it.name }
            assertTrue("${dir.name} still has the English text of $same", same.isEmpty())
        }
    }

    private class Res(val name: String, val file: String, val translatable: Boolean, val plural: Boolean, val text: String, val items: Map<String, String>)

    private fun strings(dir: File): List<File> = dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }

    private fun load(dir: File): Map<String, Res> {
        val out = LinkedHashMap<String, Res>()
        for (file in strings(dir)) {
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                if (e.tagName != "string" && e.tagName != "plurals") continue
                val items = if (e.tagName == "plurals") {
                    val list = e.getElementsByTagName("item")
                    (0 until list.length).associate { (list.item(it) as Element).let { item -> item.getAttribute("quantity") to item.textContent } }
                } else {
                    emptyMap()
                }
                val name = e.getAttribute("name")
                assertTrue("${dir.name}/${file.name} declares $name twice", name !in out)
                out[name] = Res(name, file.name, e.getAttribute("translatable") != "false", e.tagName == "plurals", e.textContent, items)
            }
        }
        return out
    }

    private companion object {
        val LOCALE_DIR = Regex("values-[a-z]{2,3}(-r[A-Z]{2})?")
        val FORMAT_ARG = Regex("%(\\d+\\$)?\\d*(\\.\\d+)?[a-zA-Z%]")

        /** Strings that are rightly the same as the English, per language tag. */
        val SAME_AS_ENGLISH = mapOf(
            "ar" to setOf("system_hotkeys_keys_ok"),
            "az" to setOf(
                "apps_device_id", "apps_device_model", "backup_ok", "home_cat_video", "settings_ok",
                "system_hotkeys_keys_ok", "wp_subreddit",
            ),
            "bg" to setOf("apps_device_id", "backup_ok", "settings_ok", "system_hotkeys_keys_ok"),
            "ca" to setOf(
                "apps_device_id", "apps_device_model", "apps_device_no", "backup_preview_categories", "editor_visible",
                "firstrun_no", "settings_contrast", "settings_general", "settings_legend_experimental", "settings_no",
                "settings_tr_zoom", "system_hotkeys_keys_ok", "wallpaper_wikimedia_animals", "wp_subreddit",
            ),
            "cs" to setOf(
                "apps_device_hardware", "apps_device_id", "apps_device_model", "backup_ok", "home_cat_video",
                "settings_ok", "system_hotkeys_keys_menu_twice", "system_hotkeys_keys_ok", "wp_subreddit",
            ),
            "da" to setOf(
                "about_links", "about_version", "apps_device_hardware", "apps_device_id", "apps_device_model",
                "backup_count_apps", "backup_ok", "backup_preview_version", "home_cat_system", "home_cat_video",
                "iconset_repo", "iconset_status", "install_status", "menu_stop", "settings_breadcrumb_system",
                "settings_ok", "settings_tr_zoom", "system_hotkeys_keys_menu_twice", "system_hotkeys_keys_ok",
                "system_hotkeys_start_launcher", "wallpaper_provider_unknown_plugin", "wp_status", "wp_subreddit",
            ),
            "de" to setOf(
                "about_links", "about_version", "apps_device_hardware", "apps_device_id", "backup_ok",
                "home_cat_system", "iconset_repo", "iconset_status", "install_status", "menu_details",
                "settings_breadcrumb_system", "settings_ok", "system_hotkeys_keys_ok", "wp_status", "wp_subreddit",
            ),
            "el" to setOf("backup_ok", "settings_ok", "system_hotkeys_keys_ok", "wp_subreddit"),
            "es" to setOf(
                "apps_device_hardware", "apps_device_id", "apps_device_no", "editor_visible", "firstrun_no",
                "settings_general", "settings_legend_experimental", "settings_no", "settings_tr_zoom",
                "system_hotkeys_keys_ok", "wp_subreddit",
            ),
            "et" to setOf(
                "apps_device_id", "backup_ok", "home_cat_video", "settings_font", "settings_font_google", "settings_ok",
                "system_hotkeys_keys_ok", "wp_subreddit",
            ),
            "fa-IR" to setOf("system_hotkeys_keys_ok"),
            "fi" to setOf("backup_ok", "settings_ok", "system_hotkeys_keys_ok", "wp_subreddit"),
            "fr" to setOf(
                "about_version", "apps_device_id", "backup_ok", "backup_preview_version", "color_luminance",
                "editor_visible", "home_cat_applications", "home_status_notifications", "iconset_style",
                "notifications_title", "settings_breadcrumb_applications", "settings_ok", "settings_saturation",
                "settings_tr_zoom", "settings_volume", "system_hotkeys_keys_menu_twice", "system_hotkeys_keys_ok",
                "wallpaper_wikimedia_architecture", "wallpaper_wikimedia_nature", "wp_source", "wp_subreddit",
            ),
            "gl-ES" to setOf(
                "apps_device_hardware", "apps_device_id", "editor_visible", "settings_legend_experimental",
                "settings_tr_zoom", "settings_volume", "system_hotkeys_keys_ok", "wp_subreddit",
            ),
            "he" to setOf("system_hotkeys_keys_ok"),
            "hi" to setOf("apps_device_id", "system_hotkeys_keys_ok"),
            "hr" to setOf(
                "apps_device_id", "apps_device_model", "home_cat_video", "settings_font", "settings_font_google",
                "system_hotkeys_keys_ok", "wp_subreddit",
            ),
            "hu" to setOf("backup_ok", "settings_ok", "system_hotkeys_keys_ok", "wp_subreddit"),
            "hy" to setOf("system_hotkeys_keys_ok"),
            "id" to setOf(
                "apps_autostart_screensaver", "apps_device_id", "apps_device_model", "backup_ok", "home_cat_video",
                "home_episode", "iconset_status", "install_status", "settings_font", "settings_font_google",
                "settings_ok", "settings_src_folder", "settings_volume", "settings_wallpaper",
                "system_hotkeys_keys_menu_twice", "system_hotkeys_keys_ok", "wallpaper_provider_folder",
                "wallpaper_provider_unknown_plugin", "wp_folder", "wp_status", "wp_subreddit",
            ),
            "it" to setOf(
                "apps_device_hardware", "apps_device_id", "apps_device_no", "backup_ok", "backup_password",
                "firstrun_no", "home_cat_display", "home_cat_video", "iconset_repo", "settings_breadcrumb_display",
                "settings_no", "settings_ok", "settings_tr_zoom", "settings_volume", "system_hotkeys_keys_home",
                "system_hotkeys_keys_menu_twice", "system_hotkeys_keys_ok", "wp_subreddit",
            ),
            "ja" to setOf("apps_device_id", "backup_ok", "settings_ok", "system_hotkeys_keys_ok"),
            "ka-GE" to setOf("apps_device_id", "backup_ok", "settings_ok", "system_hotkeys_keys_ok", "wp_subreddit"),
            "ko" to setOf("apps_device_id"),
            "lt" to setOf("apps_device_id", "system_hotkeys_keys_ok"),
            "lv" to setOf(
                "apps_device_id", "backup_ok", "home_cat_video", "settings_ok", "system_hotkeys_keys_ok",
                "wp_subreddit",
            ),
            "mo" to setOf(
                "apps_device_hardware", "apps_device_id", "apps_device_model", "backup_ok", "home_cat_video",
                "settings_contrast", "settings_font", "settings_font_google", "settings_general",
                "settings_legend_experimental", "settings_ok", "system_hotkeys_keys_ok",
                "wallpaper_provider_unknown_plugin", "wp_subreddit",
            ),
            "ms" to setOf(
                "apps_device_id", "apps_device_model", "backup_import", "backup_import_action", "backup_ok",
                "editor_grid", "home_cat_video", "iconset_status", "install_status", "settings_import", "settings_ok",
                "settings_src_folder", "system_hotkeys_keys_menu_twice", "system_hotkeys_keys_ok",
                "wallpaper_provider_folder", "wp_folder", "wp_status", "wp_subreddit",
            ),
            "nb" to setOf(
                "apps_device_id", "backup_ok", "home_cat_system", "home_cat_video", "home_episode", "iconset_status",
                "install_status", "settings_breadcrumb_system", "settings_ok", "settings_tr_zoom",
                "system_hotkeys_keys_ok", "system_hotkeys_start_launcher", "wallpaper_provider_unknown_plugin",
                "wp_status", "wp_subreddit",
            ),
            "nl" to setOf(
                "about_links", "apps_autostart_screensaver", "apps_device_hardware", "apps_device_id",
                "apps_device_model", "backup_count_apps", "backup_ok", "home_cat_games", "home_cat_video",
                "iconset_release", "iconset_repo", "iconset_status", "install_status", "menu_details",
                "settings_contrast", "settings_ok", "settings_volume", "system_hotkeys_keys_home",
                "system_hotkeys_keys_menu_twice", "system_hotkeys_keys_ok", "wp_status", "wp_subreddit",
            ),
            "pl" to setOf(
                "apps_device_id", "apps_device_model", "backup_ok", "home_cat_system", "settings_breadcrumb_system",
                "settings_ok", "settings_src_folder", "system_hotkeys_keys_menu_twice", "system_hotkeys_keys_ok",
                "wallpaper_provider_folder", "wp_folder", "wp_subreddit",
            ),
            "pt-BR" to setOf(
                "about_links", "apps_device_hardware", "apps_device_id", "backup_ok", "iconset_status",
                "install_status", "settings_legend_experimental", "settings_ok", "settings_tr_zoom", "settings_volume",
                "system_hotkeys_keys_menu_twice", "system_hotkeys_keys_ok", "wallpaper_provider_unknown_plugin",
                "wp_status", "wp_subreddit",
            ),
            "pt-PT" to setOf(
                "apps_device_hardware", "apps_device_id", "backup_ok", "settings_legend_experimental", "settings_ok",
                "settings_tr_zoom", "settings_volume", "system_hotkeys_keys_menu_twice", "system_hotkeys_keys_ok",
                "wallpaper_provider_unknown_plugin", "wp_subreddit",
            ),
            "ro" to setOf(
                "apps_device_hardware", "apps_device_id", "apps_device_model", "backup_ok", "home_cat_video",
                "settings_contrast", "settings_font", "settings_font_google", "settings_general",
                "settings_legend_experimental", "settings_ok", "system_hotkeys_keys_ok",
                "wallpaper_provider_unknown_plugin", "wp_subreddit",
            ),
            "ru" to setOf("apps_device_id", "backup_ok", "settings_ok", "system_hotkeys_keys_ok"),
            "sk" to setOf(
                "apps_device_id", "apps_device_model", "backup_ok", "home_cat_video", "settings_ok",
                "system_hotkeys_keys_menu_twice", "system_hotkeys_keys_ok", "wp_subreddit",
            ),
            "sl" to setOf(
                "apps_device_id", "apps_device_model", "home_cat_video", "system_hotkeys_keys_ok", "wp_subreddit",
            ),
            "sq" to setOf("backup_ok", "settings_ok", "system_hotkeys_keys_ok", "wp_subreddit"),
            "sr" to setOf("apps_device_id", "backup_ok", "settings_ok", "system_hotkeys_keys_ok"),
            "sv" to setOf(
                "about_version", "apps_device_id", "backup_ok", "backup_preview_version", "home_cat_system",
                "home_cat_video", "iconset_repo", "iconset_status", "install_status", "settings_breadcrumb_system",
                "settings_ok", "settings_tr_zoom", "system_hotkeys_keys_ok", "wallpaper_provider_unknown_plugin",
                "wp_status", "wp_subreddit",
            ),
            "te" to setOf("apps_device_id", "system_hotkeys_keys_ok"),
            "th" to setOf("system_hotkeys_keys_ok", "wp_subreddit"),
            "tr" to setOf("apps_device_model", "home_cat_video", "system_hotkeys_keys_ok", "wp_subreddit"),
            "uk" to setOf("apps_device_id", "backup_ok", "settings_ok", "system_hotkeys_keys_ok"),
            "vi" to setOf(
                "apps_device_id", "backup_ok", "home_cat_video", "settings_ok", "system_hotkeys_keys_menu_twice",
                "system_hotkeys_keys_ok", "wallpaper_provider_unknown_plugin", "wp_subreddit",
            ),
            "zh" to setOf("apps_device_id", "system_hotkeys_keys_ok"),
            "zh-TW" to setOf("apps_device_id", "system_hotkeys_keys_ok"),
        )

        /** Java's legacy codes, which the res folders keep: the picker offers the current ones. */
        val LEGACY_TAGS = mapOf("in" to "id", "iw" to "he")

        fun formatArgs(text: String): List<String> = FORMAT_ARG.findAll(text).map { it.value }.sorted().toList()

        /** `values-pt-rBR` → `pt-BR`; `values-iw` → `he`, `values-in` → `id`. */
        fun dirTag(dir: String): String = dir.removePrefix("values-").replace("-r", "-").let { LEGACY_TAGS[it] ?: it }
    }
}
