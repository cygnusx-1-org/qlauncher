/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.reddit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.wallpaper.Fixtures
import org.cygnusx1.qlauncher.wallpaper.WallpaperEvent
import org.cygnusx1.qlauncher.wallpaper.source.ProviderException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RedditProviderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val credentials = MemoryCredentials(RedditTokens("R1", "A1", Long.MAX_VALUE, "alice"))
    private val user = MutableStateFlow<String?>("alice")
    private lateinit var cache: RedditListCache
    private lateinit var provider: RedditProvider

    @Before
    fun setUp() {
        server.start()
        val api = RedditApi(OkHttpClient(), credentials, { 0L }, server.url("/www/"), server.url("/oauth/"))
        cache = RedditListCache(File(tmp.root, "reddit_last.json"))
        provider = RedditProvider(api, user, cache) { RedditFilter(800, true) }
    }

    @After
    fun tearDown() = server.close()

    private fun listing() = MockResponse.Builder().body(Fixtures.read("reddit_listing.json")).build()

    private suspend fun fetch(params: String = "EarthPorn") = provider.getWallpapers(WallpaperEvent.TimeElapsed, params)

    @Test
    fun readsTopOfTheMonth() = runTest {
        server.enqueue(listing())
        assertEquals(4, fetch().size)
        val r = server.takeRequest()
        assertEquals("/oauth/r/EarthPorn/top", r.url.encodedPath)
        assertEquals("month", r.url.queryParameter("t"))
    }

    @Test
    fun fallsBackToHotWhenTopIsEmpty() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"kind":"Listing","data":{"after":null,"children":[]}}""").build())
        server.enqueue(listing())
        assertEquals(4, fetch().size)
        server.takeRequest()
        val hot = server.takeRequest()
        assertEquals("/oauth/r/EarthPorn/hot", hot.url.encodedPath)
        assertEquals(null, hot.url.queryParameter("t"))
    }

    @Test
    fun a403OrA429KeepsTheCachedList() = runTest {
        server.enqueue(listing())
        assertEquals(4, fetch().size)
        server.enqueue(MockResponse.Builder().code(403).body("{}").build())
        assertEquals(4, fetch().size)
    }

    @Test
    fun a429WithoutACacheReportsTheLimit() = runTest {
        server.enqueue(MockResponse.Builder().code(429).body("{}").build())
        try {
            fetch()
            fail("expected ProviderException")
        } catch (e: ProviderException) {
            assertEquals(R.string.wallpaper_error_rate_limited, e.messageRes)
        }
    }

    @Test
    fun loggedOutServesTheCacheOrAsksForALogin() = runTest {
        server.enqueue(listing())
        fetch()
        user.value = null
        assertEquals(4, fetch().size)
        cache.clear()
        try {
            fetch()
            fail("expected ProviderException")
        } catch (e: ProviderException) {
            assertEquals(R.string.wallpaper_reddit_login_needed, e.messageRes)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun theCacheIsPerSubredditSet() = runTest {
        server.enqueue(listing())
        fetch("EarthPorn")
        user.value = null
        try {
            fetch("SkyPorn")
            fail("expected ProviderException")
        } catch (e: ProviderException) {
            assertEquals(R.string.wallpaper_reddit_login_needed, e.messageRes)
        }
    }
}
