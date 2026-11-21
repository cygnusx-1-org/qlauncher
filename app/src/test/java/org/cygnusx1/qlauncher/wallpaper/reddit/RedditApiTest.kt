/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.reddit

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Credentials
import okhttp3.OkHttpClient
import org.cygnusx1.qlauncher.wallpaper.Fixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** In-memory credentials for tests. */
class MemoryCredentials(var tokens: RedditTokens? = null) : RedditCredentials {
    override suspend fun current(): RedditTokens? = tokens
    override suspend fun save(tokens: RedditTokens) {
        this.tokens = tokens
    }
    override suspend fun clear() {
        tokens = null
    }
}

class RedditApiTest {
    private val server = MockWebServer()
    private var now = 1_000_000_000L
    private val credentials = MemoryCredentials()
    private var loggedOut = 0
    private lateinit var api: RedditApi

    @Before
    fun setUp() {
        server.start()
        api = RedditApi(
            client = OkHttpClient(),
            credentials = credentials,
            clock = { now },
            wwwBase = server.url("/www/"),
            oauthBase = server.url("/oauth/"),
            onLoggedOut = { loggedOut++ },
        )
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun loggedIn(access: String? = "A1", expiresIn: Long = 3_600_000L) {
        credentials.tokens = RedditTokens("R1", access, now + expiresIn, "alice")
    }

    @Test
    fun exchangeStoresTheTokens() = runTest {
        server.enqueue(json("""{"access_token":"A1","token_type":"bearer","expires_in":86400,"refresh_token":"R1","scope":"identity read"}"""))
        val result = api.exchangeCode("CODE")
        assertEquals(TokenResult.Success("A1", "R1", 86400), result)
        assertEquals("R1", credentials.tokens?.refreshToken)
        assertEquals("A1", credentials.tokens?.accessToken)
        assertEquals(now + 86_400_000L, credentials.tokens?.accessExpiresAt)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/www/api/v1/access_token", request.url.encodedPath)
        assertEquals(Credentials.basic("yH0aTnJEt6qUgGn835B4vg", ""), request.headers["Authorization"])
        assertEquals("org.quantumbadger.redreader/1.25.2", request.headers["User-Agent"])
        assertEquals("grant_type=authorization_code&code=CODE&redirect_uri=redreader%3A%2F%2Frr_oauth_redir", request.body?.utf8())
    }

    @Test
    fun exchangeWithInvalidGrantStoresNothing() = runTest {
        server.enqueue(json("""{"error":"invalid_grant"}""", 400))
        assertEquals(TokenResult.InvalidGrant, api.exchangeCode("CODE"))
        assertNull(credentials.tokens)
    }

    @Test
    fun exchangeWithAMalformedBodyStoresNothing() = runTest {
        server.enqueue(json("<html>not json</html>"))
        assertEquals(TokenResult.Malformed, api.exchangeCode("CODE"))
        server.enqueue(json("""{"access_token":"A1","expires_in":3600}"""))
        // A first login must bring a refresh token.
        assertEquals(TokenResult.Malformed, api.exchangeCode("CODE"))
        assertNull(credentials.tokens)
    }

    @Test
    fun refreshesWithinAMinuteOfExpiry() = runTest {
        loggedIn(expiresIn = 30_000L)
        server.enqueue(json("""{"access_token":"A2","token_type":"bearer","expires_in":3600,"scope":"identity read"}"""))
        server.enqueue(json(Fixtures.read("reddit_listing.json")))
        api.listing("EarthPorn", "top", "month")
        val refresh = server.takeRequest()
        assertEquals("/www/api/v1/access_token", refresh.url.encodedPath)
        assertEquals("grant_type=refresh_token&refresh_token=R1", refresh.body?.utf8())
        val listing = server.takeRequest()
        assertEquals("bearer A2", listing.headers["Authorization"])
        // The refresh token is kept when the reply does not repeat it.
        assertEquals("R1", credentials.tokens?.refreshToken)
        assertEquals("A2", credentials.tokens?.accessToken)
    }

    @Test
    fun noRefreshWhileTheTokenIsFresh() = runTest {
        loggedIn(expiresIn = 600_000L)
        server.enqueue(json(Fixtures.read("reddit_listing.json")))
        api.listing("EarthPorn", "top", "month")
        assertEquals(1, server.requestCount)
        assertEquals("bearer A1", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun listingRequestUsesRawJsonAndTheUserAgent() = runTest {
        loggedIn()
        server.enqueue(json(Fixtures.read("reddit_listing.json")))
        api.listing("EarthPorn+SkyPorn", "top", "month")
        val r = server.takeRequest()
        assertEquals("/oauth/r/EarthPorn+SkyPorn/top", r.url.encodedPath)
        assertEquals("1", r.url.queryParameter("raw_json"))
        assertEquals("100", r.url.queryParameter("limit"))
        assertEquals("month", r.url.queryParameter("t"))
        assertEquals("org.quantumbadger.redreader/1.25.2", r.headers["User-Agent"])
        assertTrue(!r.url.encodedPath.contains("/api/info"))
    }

    @Test
    fun a401RefreshesAndRetries() = runTest {
        loggedIn()
        server.enqueue(json("""{"message":"Unauthorized","error":401}""", 401))
        server.enqueue(json("""{"access_token":"A2","expires_in":3600,"refresh_token":"R2"}"""))
        server.enqueue(json(Fixtures.read("reddit_listing.json")))
        val body = api.listing("EarthPorn", "top", "month")
        assertTrue(body.contains("t3_next"))
        assertEquals(3, server.requestCount)
        assertEquals("R2", credentials.tokens?.refreshToken)
    }

    @Test
    fun invalidGrantOnRefreshLogsOut() = runTest {
        loggedIn(expiresIn = 0L)
        server.enqueue(json("""{"error":"invalid_grant"}""", 400))
        try {
            api.listing("EarthPorn", "top", "month")
            fail("expected NotLoggedIn")
        } catch (e: RedditException.NotLoggedIn) {
            // expected
        }
        assertNull(credentials.tokens)
        assertEquals(1, loggedOut)
    }

    @Test
    fun malformedRefreshKeepsTheTokens() = runTest {
        loggedIn(expiresIn = 0L)
        server.enqueue(json("garbage"))
        try {
            api.listing("EarthPorn", "top", "month")
            fail("expected an IOException")
        } catch (e: java.io.IOException) {
            assertTrue(e !is RedditException.NotLoggedIn)
        }
        assertEquals("R1", credentials.tokens?.refreshToken)
    }

    @Test
    fun a403BacksOffWithoutFurtherRequests() = runTest {
        loggedIn()
        server.enqueue(json("""{"message":"Forbidden","error":403}""", 403))
        try {
            api.listing("EarthPorn", "top", "month")
            fail("expected RateLimited")
        } catch (e: RedditException.RateLimited) {
            assertEquals(now + 60_000L, e.until)
        }
        try {
            api.listing("EarthPorn", "top", "month")
            fail("expected RateLimited")
        } catch (e: RedditException.RateLimited) {
            // expected, without a request
        }
        assertEquals(1, server.requestCount)
        now += 61_000L
        server.enqueue(json(Fixtures.read("reddit_listing.json")))
        api.listing("EarthPorn", "top", "month")
        assertEquals(2, server.requestCount)
    }

    @Test
    fun a429HonoursTheResetHeader() = runTest {
        loggedIn()
        server.enqueue(MockResponse.Builder().code(429).addHeader("x-ratelimit-remaining", "0").addHeader("x-ratelimit-reset", "300").body("{}").build())
        try {
            api.listing("EarthPorn", "top", "month")
            fail("expected RateLimited")
        } catch (e: RedditException.RateLimited) {
            assertEquals(now + 300_000L, e.until)
        }
    }

    @Test
    fun anExhaustedRateLimitWaitsForTheReset() = runTest {
        loggedIn()
        server.enqueue(MockResponse.Builder().addHeader("x-ratelimit-remaining", "0.0").addHeader("x-ratelimit-reset", "120").body(Fixtures.read("reddit_listing.json")).build())
        api.listing("EarthPorn", "top", "month")
        try {
            api.listing("EarthPorn", "top", "month")
            fail("expected RateLimited")
        } catch (e: RedditException.RateLimited) {
            assertEquals(now + 120_000L, e.until)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun loggedOutCallsFailWithoutARequest() = runTest {
        try {
            api.listing("EarthPorn", "top", "month")
            fail("expected NotLoggedIn")
        } catch (e: RedditException.NotLoggedIn) {
            // expected
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun meReadsTheName() = runTest {
        loggedIn()
        server.enqueue(json("""{"name":"alice","id":"abc"}"""))
        assertEquals("alice", api.me())
        assertEquals("/oauth/api/v1/me", server.takeRequest().url.encodedPath)
    }

    @Test
    fun revokeSendsTheRefreshToken() = runTest {
        loggedIn()
        server.enqueue(MockResponse.Builder().code(204).build())
        api.revoke()
        val r = server.takeRequest()
        assertEquals("/www/api/v1/revoke_token", r.url.encodedPath)
        assertEquals("token=R1&token_type_hint=refresh_token", r.body?.utf8())
    }
}
