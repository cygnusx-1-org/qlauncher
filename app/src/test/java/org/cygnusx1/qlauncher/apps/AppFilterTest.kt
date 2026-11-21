/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.apps

import android.util.Xml
import org.cygnusx1.qlauncher.apps.iconpack.AppFilter
import org.cygnusx1.qlauncher.apps.iconpack.AppFilterParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringReader

/** Runs on Robolectric for a real `XmlPullParser`; the plain Application keeps the app container out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AppFilterTest {
    private val sample = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <!-- Some apps -->
            <iconback img1="iconback" />
            <scale factor="0.9" />
            <item component="ComponentInfo{com.abc.News/com.abc.News.MainActivity}" drawable="abcnews" />
            <item component="ComponentInfo{com.disney.disneyplus/com.disney.disneyplus.MainActivity}" drawable="disneyplus" />
            <item component="ComponentInfo{com.disney.disneyplus/com.bamtechmedia.dominguez.main.MainActivity}" drawable="disneyplus_alt" />
            <item component="ComponentInfo{com.relative/.Launcher}" drawable="relative" />
            <item component="ComponentInfo{com.abc.News/com.abc.News.MainActivity}" drawable="duplicate_ignored" />
            <item component="ComponentInfo{broken}" drawable="broken" />
            <item component="ComponentInfo{com.nodrawable/com.nodrawable.Main}" drawable="  " />
            <item drawable="nocomponent" />
            <calendar component="ComponentInfo{com.cal/com.cal.Main}" prefix="calendar_" />
        </resources>
    """.trimIndent()

    private fun parse(xml: String = sample): AppFilter {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        val b = AppFilterParser.Builder()
        AppFilterParser.parse(parser, b)
        return b.build("pack.pkg", 12L)
    }

    @Test
    fun onlyWellFormedItemsAreRead() {
        val f = parse()
        assertEquals(4, f.size)
        assertEquals(
            listOf(
                "com.abc.news/com.abc.news.mainactivity",
                "com.disney.disneyplus/com.disney.disneyplus.mainactivity",
                "com.disney.disneyplus/com.bamtechmedia.dominguez.main.mainactivity",
                "com.relative/com.relative.launcher",
            ),
            f.components.keys.toList(),
        )
    }

    @Test
    fun lookupIgnoresCaseAndTheFirstMappingWins() {
        val f = parse()
        assertEquals("abcnews", f.drawableFor("com.abc.news", "com.abc.news.MainActivity"))
        assertEquals("abcnews", f.drawableFor("COM.ABC.NEWS", "COM.ABC.NEWS.MAINACTIVITY"))
        assertEquals("disneyplus_alt", f.drawableFor("com.disney.disneyplus", "com.bamtechmedia.dominguez.main.MainActivity"))
        assertEquals("relative", f.drawableFor("com.relative", ".Launcher"))
    }

    @Test
    fun anUnlistedActivityFallsBackToItsPackage() {
        val f = parse()
        assertEquals("disneyplus", f.drawableFor("com.disney.disneyplus", "com.disney.NewActivity"))
        assertNull(f.exactDrawableFor("com.disney.disneyplus", "com.disney.NewActivity"))
        assertNull(f.drawableFor("com.unknown", "com.unknown.Main"))
        assertNull(f.drawableFor("com.cal", "com.cal.Main"))
    }

    @Test
    fun theCacheFormatRoundTrips() {
        val f = parse()
        val back = AppFilter.deserialize("pack.pkg", 12L, f.serialize())
        assertEquals(f.components, back.components)
        assertEquals(f.components.keys.toList(), back.components.keys.toList())
        assertEquals("disneyplus", back.drawableFor("com.disney.disneyplus", "x.Y"))
    }

    @Test
    fun badCacheLinesAreSkipped() {
        val f = AppFilter.deserialize("p", 1, "a/b\tone\nnot a line\n\tx\ny/z\t\nc/d\ttwo\n")
        assertEquals(mapOf("a/b" to "one", "c/d" to "two"), f.components)
    }

    @Test
    fun anEmptyFileGivesAnEmptyMap() {
        assertEquals(0, parse("<resources/>").size)
    }
}
