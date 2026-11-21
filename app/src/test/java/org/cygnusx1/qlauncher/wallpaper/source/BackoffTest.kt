/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    private var now = 1_000_000L
    private val backoff = Backoff({ now })

    @Test
    fun startsAtOneMinuteAndDoubles() {
        backoff.fail()
        assertEquals(now + 60_000L, backoff.blockedUntil)
        backoff.fail()
        assertEquals(now + 120_000L, backoff.blockedUntil)
        backoff.fail()
        assertEquals(now + 240_000L, backoff.blockedUntil)
    }

    @Test
    fun capsAtSixHours() {
        repeat(20) { backoff.fail() }
        assertEquals(now + 6 * 3_600_000L, backoff.blockedUntil)
    }

    @Test
    fun honoursALongerRetryAfter() {
        backoff.fail(retryAfterSeconds = 600)
        assertEquals(now + 600_000L, backoff.blockedUntil)
        // A shorter hint does not shorten the doubling.
        backoff.fail(retryAfterSeconds = 5)
        assertEquals(now + 120_000L, backoff.blockedUntil)
    }

    @Test
    fun blocksUntilTheTimeAndResets() {
        backoff.fail()
        assertTrue(backoff.isBlocked())
        now += 60_000L
        assertFalse(backoff.isBlocked())
        backoff.reset()
        backoff.fail()
        assertEquals(now + 60_000L, backoff.blockedUntil)
    }

    @Test
    fun parsesRetryAfterSeconds() {
        assertEquals(120L, Backoff.parseRetryAfter(" 120 "))
        assertNull(Backoff.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(Backoff.parseRetryAfter(null))
    }
}
