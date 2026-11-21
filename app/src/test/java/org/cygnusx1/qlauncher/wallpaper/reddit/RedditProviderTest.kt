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

    /** The fixture: four pictures that pass the filter, then `after` = `t3_next`. */
    private fun listing() = MockResponse.Builder().body(Fixtures.read("reddit_listing.json")).build()

    /** The last page of a listing: no posts, no `after`. */
    private fun endPage() = MockResponse.Builder().body("""{"kind":"Listing","data":{"after":null,"children":[]}}""").build()

    /** A page of image posts named [names]; a name starting with `tall` is portrait. */
    private fun page(names: List<String>, after: String?): MockResponse {
        val children = names.joinToString(",") { n ->
            val (w, h) = if (n.startsWith("tall")) 2000 to 3000 else 3000 to 2000
            """{"kind":"t3","data":{"id":"$n","title":"$n","post_hint":"image",""" +
                """"preview":{"images":[{"source":{"url":"https://preview.redd.it/$n.jpg","width":$w,"height":$h}}]}}}"""
        }
        val cursor = after?.let { "\"$it\"" } ?: "null"
        return MockResponse.Builder().body("""{"kind":"Listing","data":{"after":$cursor,"children":[$children]}}""").build()
    }

    private suspend fun fetch(params: String = "EarthPorn") = provider.getWallpapers(WallpaperEvent.TimeElapsed, params)

    @Test
    fun readsTopOfTheMonth() = runTest {
        server.enqueue(listing())
        server.enqueue(endPage())
        assertEquals(4, fetch().size)
        assertEquals(2, server.requestCount)
        val r = server.takeRequest()
        assertEquals("/oauth/r/EarthPorn/top", r.url.encodedPath)
        assertEquals("month", r.url.queryParameter("t"))
        assertEquals(null, r.url.queryParameter("after"))
        val next = server.takeRequest()
        assertEquals("t3_next", next.url.queryParameter("after"))
        assertEquals("month", next.url.queryParameter("t"))
    }

    @Test
    fun fallsBackToHotWhenTopIsEmpty() = runTest {
        server.enqueue(endPage())
        server.enqueue(listing())
        server.enqueue(endPage())
        assertEquals(4, fetch().size)
        server.takeRequest()
        val hot = server.takeRequest()
        assertEquals("/oauth/r/EarthPorn/hot", hot.url.encodedPath)
        assertEquals(null, hot.url.queryParameter("t"))
    }

    @Test
    fun a403OrA429KeepsTheCachedList() = runTest {
        server.enqueue(listing())
        server.enqueue(endPage())
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
        server.enqueue(endPage())
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
        assertEquals(2, server.requestCount)
    }

    @Test
    fun theCacheIsPerSubredditSet() = runTest {
        server.enqueue(listing())
        server.enqueue(endPage())
        fetch("EarthPorn")
        user.value = null
        try {
            fetch("SkyPorn")
            fail("expected ProviderException")
        } catch (e: ProviderException) {
            assertEquals(R.string.wallpaper_reddit_login_needed, e.messageRes)
        }
    }

    @Test
    fun picturesTheFilterDropsAreMadeUpFromTheNextPage() = runTest {
        val first = (1..100).map { if (it % 2 == 0) "tall$it" else "wide$it" }
        val second = (101..200).map { "wide$it" }
        server.enqueue(page(first, after = "t3_p2"))
        server.enqueue(page(second, after = "t3_p3"))
        val items = fetch()
        // 50 pass on the first page, so the second is read; 100 are then reached and paging stops.
        assertEquals(150, items.size)
        assertEquals(first.filter { it.startsWith("wide") } + second, items.map { it.title })
        assertEquals(2, server.requestCount)
        server.takeRequest()
        assertEquals("t3_p2", server.takeRequest().url.queryParameter("after"))
    }

    @Test
    fun pagingStopsAfterFourPages() = runTest {
        for (p in 1..4) server.enqueue(page(listOf("wide$p") + (1..99).map { "tall$p-$it" }, after = "t3_p${p + 1}"))
        assertEquals(listOf("wide1", "wide2", "wide3", "wide4"), fetch().map { it.title })
        assertEquals(4, server.requestCount)
    }

    @Test
    fun aFailedLaterPageKeepsTheEarlierPictures() = runTest {
        server.enqueue(listing())
        server.enqueue(MockResponse.Builder().code(500).body("{}").build())
        assertEquals(4, fetch().size)
        assertEquals(2, server.requestCount)
    }
}
