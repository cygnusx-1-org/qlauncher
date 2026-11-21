/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.reports

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportFormatTest {
    private val app = ReportApp("org.cygnusx1.qlauncher", "0.1.0", 1, "release")
    private val system = ReportSystem(30, "11", "NVIDIA", "SHIELD Android TV", "darcy", listOf("arm64-v8a"), true)

    private fun ex(type: String, vararg frames: String) = ReportException(type, null, frames.toList())

    @Test
    fun messagesLoseQueriesAndAddressesAndAreCapped() {
        assertEquals(
            "GET https://oauth.reddit.com/api/v1/me?… failed for <email>",
            ReportFormat.scrub("GET https://oauth.reddit.com/api/v1/me?access_token=secret#x failed for nathan@example.org"),
        )
        assertEquals(ReportFormat.MAX_MESSAGE, ReportFormat.scrub("x".repeat(2000))!!.length)
        assertEquals(null, ReportFormat.scrub(null))
    }

    @Test
    fun fingerprintIgnoresLineNumbersAndPrefersOwnFrames() {
        val a = ex("java.lang.IllegalStateException", "org.cygnusx1.qlauncher.A.f(A.kt:10)", "android.os.Handler.dispatch(Handler.java:1)")
        val b = ex("java.lang.IllegalStateException", "android.view.View.x(View.java:5)", "org.cygnusx1.qlauncher.A.f(A.kt:99)")
        assertEquals(ReportFormat.fingerprint("crash", null, a), ReportFormat.fingerprint("crash", null, b))
        assertNotEquals(ReportFormat.fingerprint("crash", null, a), ReportFormat.fingerprint("error", "install", a))
        assertNotEquals(ReportFormat.fingerprint("crash", null, a), ReportFormat.fingerprint("crash", null, a.copy(type = "java.lang.NullPointerException")))
        assertTrue(Regex("^[0-9a-f]{64}$").matches(ReportFormat.fingerprint("crash", null, a)))
    }

    @Test
    fun causesAreKeptUpToTheLimitAndCyclesStop() {
        val root = IllegalStateException("outer", RuntimeException("middle", IllegalArgumentException("inner")))
        val e = ReportFormat.exceptionOf(root)
        assertEquals(listOf("java.lang.RuntimeException", "java.lang.IllegalArgumentException"), e.causes.map { it.type })

        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertEquals(1, ReportFormat.exceptionOf(a).causes.size)

        var deep: Throwable = RuntimeException("0")
        repeat(10) { deep = RuntimeException("${it + 1}", deep) }
        assertEquals(ReportFormat.MAX_CAUSES, ReportFormat.exceptionOf(deep).causes.size)
    }

    @Test
    fun occurrencesFoldIntoOneEventPerFingerprintNewestFirst() {
        val crash = ReportFormat.pending("crash", null, "main", IllegalStateException("x"), 1_000)
        val again = crash.copy(at = 3_000)
        val error = ReportFormat.pending("error", "install", "io", IllegalArgumentException("y"), 2_000)
        val (report, covered) = ReportFormat.build(listOf(crash, error, again), app, system, now = 5_000)
        assertEquals(listOf(crash.fingerprint, error.fingerprint), report.events.map { it.fingerprint })
        assertEquals(2, report.events[0].count)
        assertEquals(ReportFormat.iso(1_000), report.events[0].firstAt)
        assertEquals(ReportFormat.iso(3_000), report.events[0].lastAt)
        assertEquals(3, covered.size)
    }

    @Test
    fun aBatchHoldsAtMostFiftyEvents() {
        val many = (0 until 60).map { i -> ReportFormat.pending("error", "site$i", "main", RuntimeException(), i.toLong()) }
        val (report, covered) = ReportFormat.build(many, app, system, now = 100)
        assertEquals(ReportFormat.MAX_EVENTS, report.events.size)
        assertEquals("site59", report.events.first().where)
        assertEquals(ReportFormat.MAX_EVENTS, covered.size)
    }

    @Test
    fun theJsonMatchesTheContract() {
        val event = ReportFormat.pending("crash", null, "main", IllegalStateException("x"), 0)
        val (report, _) = ReportFormat.build(listOf(event), app, system, now = 0)
        val root = ReportFormat.json.parseToJsonElement(ReportFormat.json.encodeToString(Report.serializer(), report)).jsonObject
        assertEquals("1", root["schema"]!!.jsonPrimitive.content)
        assertEquals("org.cygnusx1.qlauncher", root["app"]!!.jsonObject["package"]!!.jsonPrimitive.content)
        val first = root["events"]!!.jsonArray[0] as JsonObject
        assertEquals(JsonNull, first["where"])
        assertEquals("1", first["count"]!!.jsonPrimitive.content)
        assertTrue(first["exception"]!!.jsonObject.containsKey("causes"))
    }

    @Test
    fun isoIsWhatInstantToStringWrites() {
        for (ms in listOf(0L, 1_000L, 1_234L, 1_763_700_000_000L, 1_763_700_000_007L, 1_763_700_000_120L)) {
            assertEquals(java.time.Instant.ofEpochMilli(ms).toString(), ReportFormat.iso(ms))
        }
    }
}
