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
            "fi" to setOf("backup_ok", "settings_ok", "system_hotkeys_keys_ok", "wp_subreddit"),
        )

        fun formatArgs(text: String): List<String> = FORMAT_ARG.findAll(text).map { it.value }.sorted().toList()

        /** `values-pt-rBR` → `pt-BR`. */
        fun dirTag(dir: String): String = dir.removePrefix("values-").replace("-r", "-")
    }
}
