/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.source

/**
 * Failure backoff for a web source: 1 minute after the first failure, doubling with each further
 * one up to 6 hours, never shorter than a server's `Retry-After`.
 */
class Backoff(
    private val clock: () -> Long,
    private val baseMillis: Long = 60_000L,
    private val maxMillis: Long = 6 * 60 * 60_000L,
) {
    private var failures = 0

    /** Requests are held back until this time (clock milliseconds). */
    var blockedUntil: Long = 0L
        private set

    fun isBlocked(): Boolean = clock() < blockedUntil

    /** Records a failure; [retryAfterSeconds] is the server's hint, honoured when longer. */
    fun fail(retryAfterSeconds: Long? = null) {
        failures = (failures + 1).coerceAtMost(MAX_STEPS)
        val doubled = baseMillis shl (failures - 1)
        val hinted = (retryAfterSeconds ?: 0L).coerceAtLeast(0L) * 1000L
        blockedUntil = clock() + maxOf(doubled, hinted).coerceAtMost(maxMillis)
    }

    fun reset() {
        failures = 0
        blockedUntil = 0L
    }

    companion object {
        private const val MAX_STEPS = 10

        /** Parses a `Retry-After` header given in seconds; dates are ignored. */
        fun parseRetryAfter(value: String?): Long? = value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
    }
}
