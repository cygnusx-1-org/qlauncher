/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.reddit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RedditOAuthTest {
    private val state = "4f2a9c"

    @Test
    fun matchingStateExchangesTheCode() {
        assertEquals(
            RedirectResult.Exchange("abc-123_x"),
            RedditOAuth.classifyRedirect("redreader://rr_oauth_redir?state=$state&code=abc-123_x", state),
        )
        // Reddit sometimes appends a fragment.
        assertEquals(
            RedirectResult.Exchange("abc"),
            RedditOAuth.classifyRedirect("redreader://rr_oauth_redir?state=$state&code=abc#_", state),
        )
    }

    @Test
    fun wrongOrMissingStateIsRejected() {
        assertEquals(RedirectResult.StateMismatch, RedditOAuth.classifyRedirect("redreader://rr_oauth_redir?state=other&code=abc", state))
        assertEquals(RedirectResult.StateMismatch, RedditOAuth.classifyRedirect("redreader://rr_oauth_redir?code=abc", state))
        assertEquals(RedirectResult.StateMismatch, RedditOAuth.classifyRedirect("redreader://rr_oauth_redir?state=$state&code=abc", null))
    }

    @Test
    fun accessDeniedAndOtherErrors() {
        assertEquals(RedirectResult.AccessDenied, RedditOAuth.classifyRedirect("redreader://rr_oauth_redir?state=$state&error=access_denied", state))
        assertEquals(RedirectResult.Error("invalid_scope"), RedditOAuth.classifyRedirect("redreader://rr_oauth_redir?state=$state&error=invalid_scope", state))
        // A code wins over an error.
        assertEquals(RedirectResult.Exchange("c"), RedditOAuth.classifyRedirect("redreader://rr_oauth_redir?state=$state&error=x&code=c", state))
    }

    @Test
    fun otherPagesAreNotRedirects() {
        assertEquals(RedirectResult.NotRedirect, RedditOAuth.classifyRedirect("https://www.reddit.com/login/?code=abc&state=$state", state))
        assertEquals(RedirectResult.NotRedirect, RedditOAuth.classifyRedirect("redreader://rr_oauth_redir", state))
        assertFalse(RedditOAuth.isRedirect("https://i.reddit.com/api/v1/authorize"))
    }

    @Test
    fun codeIsRedactedInLogs() {
        assertEquals(
            "redreader://rr_oauth_redir?state=s&code=<redacted>&x=1",
            RedditOAuth.redact("redreader://rr_oauth_redir?state=s&code=SECRET&x=1"),
        )
        assertEquals("redreader://rr_oauth_redir?code=<redacted>", RedditOAuth.redact("redreader://rr_oauth_redir?code=SECRET"))
    }

    @Test
    fun authorizeUrlMatchesThePlan() {
        assertEquals(
            "https://i.reddit.com/api/v1/authorize?client_id=yH0aTnJEt6qUgGn835B4vg&response_type=code&state=$state" +
                "&redirect_uri=redreader://rr_oauth_redir&duration=permanent&scope=identity%20read",
            RedditOAuth.authorizeUrl(state),
        )
        assertTrue(RedditOAuth.authorizeUrl(state, RedditOAuth.AUTHORIZE_URL_FALLBACK).startsWith("https://www.reddit.com/api/v1/authorize.compact?"))
    }

    @Test
    fun statesAreRandom() {
        val a = RedditOAuth.newState()
        assertEquals(48, a.length)
        assertNotEquals(a, RedditOAuth.newState())
    }

    @Test
    fun tokenRepliesAreClassified() {
        assertEquals(
            TokenResult.Success("A", "R", 86400),
            RedditOAuth.classifyTokenResponse(200, """{"access_token":"A","token_type":"bearer","expires_in":86400,"refresh_token":"R","scope":"identity read"}"""),
        )
        assertEquals(TokenResult.Success("A", null, 3600), RedditOAuth.classifyTokenResponse(200, """{"access_token":"A","expires_in":3600}"""))
        assertEquals(TokenResult.InvalidGrant, RedditOAuth.classifyTokenResponse(400, """{"error":"invalid_grant"}"""))
        assertEquals(TokenResult.InvalidGrant, RedditOAuth.classifyTokenResponse(200, """{"error":"invalid_grant"}"""))
        assertEquals(TokenResult.RedditError("unsupported_grant_type"), RedditOAuth.classifyTokenResponse(200, """{"error":"unsupported_grant_type"}"""))
        assertEquals(TokenResult.HttpError(500), RedditOAuth.classifyTokenResponse(500, "<html>oops</html>"))
        assertEquals(TokenResult.Malformed, RedditOAuth.classifyTokenResponse(200, "<html>"))
        assertEquals(TokenResult.Malformed, RedditOAuth.classifyTokenResponse(200, ""))
        assertEquals(TokenResult.Malformed, RedditOAuth.classifyTokenResponse(200, """{"access_token":null,"refresh_token":"R"}"""))
        assertEquals(TokenResult.Malformed, RedditOAuth.classifyTokenResponse(200, """{"error":null}"""))
    }
}
