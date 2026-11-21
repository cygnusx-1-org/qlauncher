/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.fonts

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FontsTest {
    @get:Rule
    val tmp = TemporaryFolder(File("build/test-tmp").apply { mkdirs() })

    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun css(url: String) = """
        /* latin */
        @font-face {
          font-family: 'Open Sans';
          font-style: normal;
          font-weight: 700;
          src: url($url) format('truetype');
        }
    """.trimIndent()

    private val ttf = Buffer().write(byteArrayOf(0, 1, 0, 0, 0, 0x10, 0, 0x80.toByte(), 0, 3, 0, 0x70))

    @Test
    fun `the list is Default, the 13 system families, then the Google families`() {
        val google = File("src/main/assets/${FontCatalog.GOOGLE_FONTS_ASSET}").inputStream().use(FontCatalog::parseGoogleFamilies)
        assertTrue(google.size > 1500)
        assertTrue("Jura" in google && "Atma" in google && "DM Sans" in google)
        assertEquals(google.sortedBy { it.lowercase() }, google)
        val entries = FontCatalog.entries(google, "Default")
        assertEquals(1 + 13 + google.size, entries.size)
        assertEquals(FontEntry("default", "Default", FontSource.Default), entries[0])
        assertEquals("Sans-serif-thin", entries[4].label)
        assertEquals(FontSource.System, entries[13].source)
        assertEquals("serif-monospace", entries[13].name)
        assertEquals(FontSource.Google, entries[14].source)
        assertTrue(entries[14].label.startsWith(FontCatalog.GOOGLE_MARK))
    }

    @Test
    fun `names are recognised regardless of case`() {
        assertTrue(FontCatalog.isDefault("Default"))
        assertTrue(FontCatalog.isDefault(""))
        assertEquals("sans-serif-condensed", FontCatalog.systemFamily(" Sans-Serif-Condensed "))
        assertNull(FontCatalog.systemFamily("Jura"))
    }

    @Test
    fun `css url names the family, italic flag and weight`() {
        val css = GoogleFontsCss(OkHttpClient(), tmp.newFolder())
        assertEquals(
            "https://fonts.googleapis.com/css2?family=Open+Sans:ital,wght@1,700",
            css.cssUrl("Open Sans", 700, true).toString(),
        )
        assertEquals("https://fonts.googleapis.com/css2?family=Jura:ital,wght@0,400", css.cssUrl("Jura", 400, false).toString())
        assertEquals("https://a/b.ttf", GoogleFontsCss.fontUrl("src: url(https://a/b.ttf) format('truetype');"))
        assertEquals("https://a/c.ttf", GoogleFontsCss.fontUrl("src: url('https://a/c.ttf');"))
    }

    @Test
    fun `downloads once and then uses the cached file`() = runBlocking {
        val dir = tmp.newFolder()
        val css = GoogleFontsCss(OkHttpClient(), dir, server.url("/css2"))
        server.enqueue(MockResponse.Builder().body(css(server.url("/f/open-sans-700.ttf").toString())).build())
        server.enqueue(MockResponse.Builder().body(ttf.copy()).build())
        val first = css.fetch("Open Sans", 700, false)!!
        assertEquals(700, first.weight)
        assertFalse(first.italic)
        assertEquals(File(dir, "Open_Sans_700_n.ttf"), first.file)
        assertEquals("/css2?family=Open+Sans:ital,wght@0,700", server.takeRequest().target)
        assertEquals(2, server.requestCount)

        val second = css.fetch("Open Sans", 700, false)!!
        assertEquals(first.file, second.file)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `an unpublished style falls back to the regular one`() = runBlocking {
        val css = GoogleFontsCss(OkHttpClient(), tmp.newFolder(), server.url("/css2"))
        server.enqueue(MockResponse.Builder().code(400).body("bad axis").build())
        server.enqueue(MockResponse.Builder().body(css(server.url("/f/x.ttf").toString())).build())
        server.enqueue(MockResponse.Builder().body(ttf.copy()).build())
        val f = css.fetch("Jura", 700, true)!!
        assertEquals(700, f.weight)
        assertFalse(f.italic)
        assertEquals("/css2?family=Jura:ital,wght@1,700", server.takeRequest().target)
        assertEquals("/css2?family=Jura:ital,wght@0,700", server.takeRequest().target)
    }

    @Test
    fun `woff2 or a network failure gives nothing`() = runBlocking {
        val css = GoogleFontsCss(OkHttpClient(), tmp.newFolder(), server.url("/css2"))
        server.enqueue(MockResponse.Builder().body(css(server.url("/f/x.woff2").toString())).build())
        server.enqueue(MockResponse.Builder().body(Buffer().writeUtf8("wOF2....")).build())
        assertNull(css.fetch("Jura", 400, false))
        server.enqueue(MockResponse.Builder().code(500).build())
        assertNull(css.fetch("Jura", 400, false))
    }
}
