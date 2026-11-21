/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.apps.iconpack

import org.cygnusx1.qlauncher.apps.AppKeys
import org.xmlpull.v1.XmlPullParser

/**
 * An icon pack's `appfilter.xml`, parsed: component to drawable name.
 *
 * Lookups are by lower-cased package and class. When the exact activity is not listed, the first
 * entry for the same package answers, since packs are often written against an older activity
 * name of the same app.
 */
class AppFilter(
    val packPackage: String,
    val versionCode: Long,
    /** Lower-cased `pkg/cls` to drawable name, in file order. */
    val components: Map<String, String>,
) {
    private val byPackage: Map<String, String> = LinkedHashMap<String, String>().also { m ->
        for ((k, v) in components) m.putIfAbsent(k.substringBefore('/'), v)
    }

    val size: Int get() = components.size

    fun drawableFor(packageName: String, className: String): String? =
        components[AppKeys.lookupKey(packageName, AppKeys.fullClassName(packageName, className))]
            ?: byPackage[packageName.lowercase()]

    /** Only the exact activity, without the package fallback. */
    fun exactDrawableFor(packageName: String, className: String): String? =
        components[AppKeys.lookupKey(packageName, AppKeys.fullClassName(packageName, className))]

    /** One `component<TAB>drawable` line per entry: the on-disk cache format. */
    fun serialize(): String = buildString {
        for ((k, v) in components) append(k).append('\t').append(v).append('\n')
    }

    companion object {
        fun deserialize(packPackage: String, versionCode: Long, text: String): AppFilter {
            val map = LinkedHashMap<String, String>()
            for (line in text.lineSequence()) {
                val tab = line.indexOf('\t')
                if (tab <= 0 || tab == line.length - 1) continue
                map.putIfAbsent(line.substring(0, tab), line.substring(tab + 1))
            }
            return AppFilter(packPackage, versionCode, map)
        }
    }
}

/** Reads `<item component="ComponentInfo{pkg/cls}" drawable="name"/>` entries. */
object AppFilterParser {
    class Builder {
        private val map = LinkedHashMap<String, String>()

        val isEmpty: Boolean get() = map.isEmpty()

        /** Adds one entry; malformed ones are skipped and the first mapping of a component wins. */
        fun add(component: String?, drawable: String?) {
            val key = component?.let(AppKeys::appFilterComponent) ?: return
            val name = drawable?.trim()?.takeIf { it.isNotEmpty() } ?: return
            map.putIfAbsent(key, name)
        }

        fun build(packPackage: String, versionCode: Long) = AppFilter(packPackage, versionCode, LinkedHashMap(map))
    }

    /** Reads every `item` element of [parser] into [into]; other elements (calendar, scale, ...) are ignored. */
    fun parse(parser: XmlPullParser, into: Builder) {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                into.add(parser.getAttributeValue(null, "component"), parser.getAttributeValue(null, "drawable"))
            }
            event = parser.next()
        }
    }
}
