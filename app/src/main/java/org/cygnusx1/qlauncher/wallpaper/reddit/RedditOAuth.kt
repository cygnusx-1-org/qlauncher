/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.reddit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom

/** What an OAuth redirect tells the login screen to do. */
sealed interface RedirectResult {
    /** A code with the expected state: exchange it. */
    data class Exchange(val code: String) : RedirectResult

    /** A code arrived with a missing or different state (possible CSRF): reject it. */
    data object StateMismatch : RedirectResult

    /** The user declined. */
    data object AccessDenied : RedirectResult

    /** Another `error=` value. */
    data class Error(val error: String) : RedirectResult

    /** Not our redirect, or a redirect with neither code nor error: keep loading. */
    data object NotRedirect : RedirectResult
}

/** A classified token-endpoint reply. */
sealed interface TokenResult {
    /** [refreshToken] is null when a refresh reply does not repeat it. */
    data class Success(val accessToken: String, val refreshToken: String?, val expiresInSeconds: Long) : TokenResult

    /** `invalid_grant`: the user revoked access or the code was used. */
    data object InvalidGrant : TokenResult

    data class RedditError(val error: String) : TokenResult
    data class HttpError(val code: Int) : TokenResult
    data object Malformed : TokenResult
    data class NetworkError(val cause: Throwable) : TokenResult
}

/** The pure parts of the login flow: URLs, state, redirect and token-reply classification. */
object RedditOAuth {
    const val SCOPES = "identity read"

    /** Slide gets the compact page by using this host; it works with the client id. */
    const val AUTHORIZE_URL = "https://i.reddit.com/api/v1/authorize"

    /** The documented equivalent, used when the first one does not load. */
    const val AUTHORIZE_URL_FALLBACK = "https://www.reddit.com/api/v1/authorize.compact"

    /** The authorize page for [state]. */
    fun authorizeUrl(state: String, base: String = AUTHORIZE_URL): String =
        "$base?client_id=${RedditAuth.CLIENT_ID}&response_type=code&state=$state" +
            "&redirect_uri=${RedditAuth.REDIRECT_URI}&duration=permanent&scope=identity%20read"

    /** A random state: 24 bytes, URL-safe hex. */
    fun newState(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isRedirect(url: String): Boolean = url.startsWith(RedditAuth.REDIRECT_URI)

    /**
     * Classifies a URL the WebView is about to load. A code takes precedence over an error; a code
     * is accepted only with exactly [expectedState].
     */
    fun classifyRedirect(url: String, expectedState: String?): RedirectResult {
        if (!isRedirect(url)) return RedirectResult.NotRedirect
        val params = queryParams(url)
        val code = params["code"]
        val state = params["state"]
        val error = params["error"]
        if (!code.isNullOrEmpty()) {
            return if (expectedState != null && expectedState == state) RedirectResult.Exchange(code) else RedirectResult.StateMismatch
        }
        if (error == "access_denied") return RedirectResult.AccessDenied
        if (!error.isNullOrEmpty()) return RedirectResult.Error(error)
        return RedirectResult.NotRedirect
    }

    /** [url] with the value of every `code` parameter replaced, for logs. */
    fun redact(url: String): String = url.replace(Regex("([?&#]code=)[^&#]*"), "$1<redacted>")

    /** Query (and fragment) parameters of [url], decoded; the first value of each name wins. */
    fun queryParams(url: String): Map<String, String> {
        val raw = runCatching { URI(url) }.getOrNull()?.let { listOfNotNull(it.rawQuery, it.rawFragment).joinToString("&") }
            ?: url.substringAfter('?', "").replace('#', '&')
        val out = LinkedHashMap<String, String>()
        for (part in raw.split('&')) {
            if (part.isEmpty()) continue
            val name = decode(part.substringBefore('='))
            val value = decode(part.substringAfter('=', ""))
            out.putIfAbsent(name, value)
        }
        return out
    }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /**
     * Classifies a token-endpoint reply from its status and body. Reddit reports some failures
     * (such as `invalid_grant`) with HTTP 400 or even 200 and an `error` field.
     */
    fun classifyTokenResponse(httpCode: Int, body: String?): TokenResult {
        val obj = body?.takeIf { it.isNotBlank() }?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val error = (obj?.get("error") as? JsonPrimitive)?.takeIf { obj["error"] !is JsonNull }?.contentOrNull
        if (error == "invalid_grant") return TokenResult.InvalidGrant
        if (httpCode !in 200..299) return if (error != null) TokenResult.RedditError(error) else TokenResult.HttpError(httpCode)
        if (obj == null) return TokenResult.Malformed
        if (error != null) return TokenResult.RedditError(error)
        val access = (obj["access_token"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (access.isNullOrEmpty()) return TokenResult.Malformed
        val refresh = (obj["refresh_token"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.ifEmpty { null }
        val expires = (obj["expires_in"] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() } ?: 3600L
        return TokenResult.Success(access, refresh, expires)
    }
}
