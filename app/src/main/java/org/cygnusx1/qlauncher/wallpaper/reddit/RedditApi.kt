/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.reddit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.cygnusx1.qlauncher.wallpaper.source.Backoff
import org.cygnusx1.qlauncher.wallpaper.source.await
import java.io.IOException

/** Failures of an authorised Reddit request. */
sealed class RedditException(message: String) : IOException(message) {
    /** No refresh token, or Reddit revoked it (`invalid_grant`). */
    class NotLoggedIn : RedditException("not logged in")

    /** 403/429 or an exhausted rate limit; requests wait until [until] (clock ms). */
    class RateLimited(val until: Long) : RedditException("rate limited")

    class Http(val code: Int) : RedditException("HTTP $code")
}

/**
 * Reddit's OAuth endpoints as the logged-in user: token exchange and refresh,
 * `/api/v1/me`, subreddit listings with `raw_json=1`, revoke. Never calls `/api/info`, which
 * answers 403 for this client id.
 *
 * The access token is refreshed when it is within a minute of expiry or on a 401; a refresh that
 * fails with `invalid_grant` clears the tokens (the user revoked access). `x-ratelimit-remaining`
 * and `x-ratelimit-reset` are honoured, and 403/429 start a backoff.
 */
class RedditApi(
    private val client: OkHttpClient,
    private val credentials: RedditCredentials,
    private val clock: () -> Long = System::currentTimeMillis,
    private val wwwBase: HttpUrl = "https://www.reddit.com/".toHttpUrl(),
    private val oauthBase: HttpUrl = "https://oauth.reddit.com/".toHttpUrl(),
    private val onLoggedOut: suspend () -> Unit = {},
) {
    private val refreshMutex = Mutex()
    private val backoff = Backoff(clock)

    @Volatile
    private var rateLimitedUntil = 0L

    /** Exchanges a login [code] for tokens and stores them. */
    suspend fun exchangeCode(code: String): TokenResult {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", RedditAuth.REDIRECT_URI)
            .build()
        val result = tokenRequest(body)
        if (result is TokenResult.Success) {
            val refresh = result.refreshToken ?: return TokenResult.Malformed
            credentials.save(RedditTokens(refresh, result.accessToken, expiry(result.expiresInSeconds), null))
        }
        return result
    }

    /** Refreshes the access token. `invalid_grant` logs out. */
    suspend fun refresh(): TokenResult = refreshMutex.withLock { refreshLocked(null) }

    private suspend fun refreshLocked(staleAccess: String?): TokenResult {
        val tokens = credentials.current() ?: return TokenResult.InvalidGrant
        // Another caller refreshed while this one waited for the lock.
        if (staleAccess != null && tokens.accessToken != null && tokens.accessToken != staleAccess && !expiresSoon(tokens)) {
            return TokenResult.Success(tokens.accessToken, null, (tokens.accessExpiresAt - clock()) / 1000)
        }
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", tokens.refreshToken)
            .build()
        val result = tokenRequest(body)
        when (result) {
            is TokenResult.Success -> credentials.save(tokens.withAccess(result.accessToken, expiry(result.expiresInSeconds), result.refreshToken))
            TokenResult.InvalidGrant -> {
                credentials.clear()
                onLoggedOut()
            }
            else -> Unit
        }
        return result
    }

    /** The user name of the logged-in account. */
    suspend fun me(): String {
        val body = authorizedGet(oauthBase.newBuilder().addPathSegments("api/v1/me").addQueryParameter("raw_json", "1").build())
        val obj = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        return (obj?.get("name") as? JsonPrimitive)?.contentOrNull ?: throw IOException("no name in /api/v1/me")
    }

    /**
     * One listing page of `r/{subs}/{sort}`: the raw JSON body.
     *
     * @param subs subreddit names joined with `+`.
     * @param time `t` for top listings (`month`), null for others.
     */
    suspend fun listing(subs: String, sort: String, time: String?, limit: Int = 100, after: String? = null): String {
        val url = oauthBase.newBuilder()
            .addPathSegment("r")
            .addPathSegment(subs)
            .addPathSegment(sort)
            .addQueryParameter("raw_json", "1")
            .addQueryParameter("limit", limit.toString())
            .apply {
                if (time != null) addQueryParameter("t", time)
                if (after != null) addQueryParameter("after", after)
            }
            .build()
        return authorizedGet(url)
    }

    /** Revokes the refresh token on Reddit; failures are ignored (the local tokens go anyway). */
    suspend fun revoke() {
        val tokens = credentials.current() ?: return
        val body = FormBody.Builder()
            .add("token", tokens.refreshToken)
            .add("token_type_hint", "refresh_token")
            .build()
        val request = Request.Builder()
            .url(wwwBase.newBuilder().addPathSegments("api/v1/revoke_token").build())
            .header("Authorization", Credentials.basic(RedditAuth.CLIENT_ID, ""))
            .header("User-Agent", RedditAuth.USER_AGENT)
            .post(body)
            .build()
        runCatching { client.newCall(request).await().close() }
    }

    private suspend fun authorizedGet(url: HttpUrl): String {
        val now = clock()
        if (now < rateLimitedUntil || backoff.isBlocked()) throw RedditException.RateLimited(maxOf(rateLimitedUntil, backoff.blockedUntil))
        val tokens = credentials.current() ?: throw RedditException.NotLoggedIn()
        var access = tokens.accessToken
        if (access == null || expiresSoon(tokens)) access = refreshedAccess(access)
        var response = get(url, access)
        if (response.code == 401) {
            response.close()
            access = refreshedAccess(access)
            response = get(url, access)
        }
        response.use { r ->
            readRateLimit(r)
            when (r.code) {
                in 200..299 -> {
                    backoff.reset()
                    return withContext(Dispatchers.IO) { r.body.string() }
                }
                401 -> throw RedditException.NotLoggedIn()
                403, 429 -> {
                    backoff.fail(r.header("x-ratelimit-reset")?.toDoubleOrNull()?.toLong() ?: Backoff.parseRetryAfter(r.header("Retry-After")))
                    throw RedditException.RateLimited(backoff.blockedUntil)
                }
                else -> throw RedditException.Http(r.code)
            }
        }
    }

    private suspend fun refreshedAccess(stale: String?): String = refreshMutex.withLock {
        when (val r = refreshLocked(stale)) {
            is TokenResult.Success -> r.accessToken
            TokenResult.InvalidGrant -> throw RedditException.NotLoggedIn()
            is TokenResult.NetworkError -> throw IOException("token refresh failed", r.cause)
            is TokenResult.HttpError -> if (r.code == 429) throw RedditException.RateLimited(clock() + 60_000L) else throw RedditException.Http(r.code)
            else -> throw IOException("token refresh failed")
        }
    }

    private suspend fun get(url: HttpUrl, access: String): Response {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "bearer $access")
            .header("User-Agent", RedditAuth.USER_AGENT)
            .build()
        return client.newCall(request).await()
    }

    private suspend fun tokenRequest(body: FormBody): TokenResult {
        val request = Request.Builder()
            .url(wwwBase.newBuilder().addPathSegments("api/v1/access_token").build())
            .header("Authorization", Credentials.basic(RedditAuth.CLIENT_ID, ""))
            .header("User-Agent", RedditAuth.USER_AGENT)
            .post(body)
            .build()
        return try {
            client.newCall(request).await().use { r ->
                val text = withContext(Dispatchers.IO) { r.body.string() }
                RedditOAuth.classifyTokenResponse(r.code, text)
            }
        } catch (e: IOException) {
            TokenResult.NetworkError(e)
        }
    }

    private fun readRateLimit(r: Response) {
        val remaining = r.header("x-ratelimit-remaining")?.toDoubleOrNull() ?: return
        val reset = r.header("x-ratelimit-reset")?.toDoubleOrNull() ?: return
        if (remaining < 1.0) rateLimitedUntil = clock() + (reset * 1000).toLong()
    }

    private fun expiresSoon(t: RedditTokens): Boolean = clock() >= t.accessExpiresAt - REFRESH_MARGIN_MS

    private fun expiry(expiresInSeconds: Long): Long = clock() + expiresInSeconds * 1000L

    companion object {
        /** Refresh when the access token has less than this left. */
        const val REFRESH_MARGIN_MS = 60_000L
    }
}
