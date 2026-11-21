/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.source

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.wallpaper.Fixtures
import org.cygnusx1.qlauncher.wallpaper.WallpaperEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WikimediaProviderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private var now = 10_000_000L
    private lateinit var provider: WikimediaProvider

    @Before
    fun setUp() {
        server.start()
        provider = WikimediaProvider(OkHttpClient(), tmp.newFolder("cache"), { now }, server.url("/w/api.php"))
    }

    @After
    fun tearDown() = server.close()

    private suspend fun fetch(params: String = "MOUNTAINS") = provider.getWallpapers(WallpaperEvent.TimeElapsed, params)

    @Test
    fun cachesTheListOnDiskForADay() = runTest {
        server.enqueue(MockResponse.Builder().body(Fixtures.read("wikimedia_search.json")).build())
        assertEquals(4, fetch().size)
        now += 23 * 3_600_000L
        assertEquals(4, fetch().size)
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("incategory:\"Featured pictures of mountains\"", request.url.queryParameter("gsrsearch"))
        now += 2 * 3_600_000L
        server.enqueue(MockResponse.Builder().body(Fixtures.read("wikimedia_search.json")).build())
        fetch()
        assertEquals(2, server.requestCount)
    }

    @Test
    fun rateLimitHonoursRetryAfterAndBacksOff() = runTest {
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "600").build())
        try {
            fetch()
            fail("expected a ProviderException")
        } catch (e: ProviderException) {
            assertEquals(R.string.wallpaper_error_rate_limited, e.messageRes)
        }
        // Within the 10 minutes nothing is requested.
        now += 300_000L
        try {
            fetch()
            fail("expected a ProviderException")
        } catch (e: ProviderException) {
            assertEquals(R.string.wallpaper_error_rate_limited, e.messageRes)
        }
        assertEquals(1, server.requestCount)
        now += 301_000L
        server.enqueue(MockResponse.Builder().body(Fixtures.read("wikimedia_search.json")).build())
        assertEquals(4, fetch().size)
    }

    @Test
    fun aFailureServesTheStaleList() = runTest {
        server.enqueue(MockResponse.Builder().body(Fixtures.read("wikimedia_search.json")).build())
        fetch()
        now += 25 * 3_600_000L
        server.enqueue(MockResponse.Builder().code(503).build())
        assertEquals(4, fetch().size)
    }

    @Test
    fun emptyCustomSearchMakesNoRequest() = runTest {
        assertEquals(0, fetch("q:   ").size)
        assertEquals(0, server.requestCount)
    }
}
