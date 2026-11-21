/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.reports

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * The anonymous crash and error report, schema 1, as QLauncherDataRestAPI takes it
 * (`schema/report.schema.json` there). Nothing in it identifies the user: the app version, the
 * device model and the exceptions, with URLs' queries and e-mail addresses taken out of messages.
 */

@Serializable
data class Report(
    val schema: Int = ReportFormat.SCHEMA,
    val sentAt: String,
    val app: ReportApp,
    val system: ReportSystem,
    val events: List<ReportEvent>,
)

@Serializable
data class ReportApp(
    @SerialName("package") val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val buildType: String,
)

@Serializable
data class ReportSystem(
    val sdkInt: Int,
    val release: String,
    val make: String,
    val model: String,
    val device: String,
    val abis: List<String>,
    val tv: Boolean,
)

@Serializable
data class ReportEvent(
    val kind: String,
    val where: String?,
    val thread: String,
    val firstAt: String,
    val lastAt: String,
    val count: Int,
    val fingerprint: String,
    val exception: ReportException,
)

@Serializable
data class ReportException(
    val type: String,
    val message: String?,
    val frames: List<String>,
    val causes: List<ReportCause> = emptyList(),
)

@Serializable
data class ReportCause(val type: String, val message: String?, val frames: List<String>)

/** One occurrence, as written to disk until it is sent. */
@Serializable
data class PendingEvent(
    val kind: String,
    val where: String?,
    val thread: String,
    val at: Long,
    val fingerprint: String,
    val exception: ReportException,
)

object ReportFormat {
    const val SCHEMA = 1
    const val KIND_CRASH = "crash"
    const val KIND_ERROR = "error"
    const val MAX_EVENTS = 50
    const val MAX_FRAMES = 64
    const val MAX_CAUSES = 5
    const val MAX_MESSAGE = 500

    /** Frames that make a fingerprint: enough to tell two bugs apart, few enough to survive edits elsewhere. */
    private const val FINGERPRINT_FRAMES = 8
    private const val OWN_PACKAGE = "org.cygnusx1.qlauncher."

    val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }

    private val URL_TAIL = Regex("""(https?://[^\s?#"']+)[?#][^\s"']*""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    /** One occurrence of [e] now. */
    fun pending(kind: String, where: String?, thread: String, e: Throwable, at: Long): PendingEvent {
        val exception = exceptionOf(e)
        return PendingEvent(kind, where, thread, at, fingerprint(kind, where, exception), exception)
    }

    fun exceptionOf(e: Throwable): ReportException {
        val causes = ArrayList<ReportCause>()
        val seen = HashSet<Throwable>().apply { add(e) }
        var cause = e.cause
        while (cause != null && causes.size < MAX_CAUSES && seen.add(cause)) {
            causes += ReportCause(cause.javaClass.name, scrub(cause.message), frames(cause))
            cause = cause.cause
        }
        return ReportException(e.javaClass.name, scrub(e.message), frames(e), causes)
    }

    private fun frames(e: Throwable): List<String> = e.stackTrace.take(MAX_FRAMES).map { it.toString() }

    /** A message without URL queries and fragments (tokens live there) or e-mail addresses, capped. */
    fun scrub(message: String?): String? {
        if (message == null) return null
        val cleaned = message.replace(URL_TAIL, "$1?…").replace(EMAIL, "<email>")
        return if (cleaned.length <= MAX_MESSAGE) cleaned else cleaned.take(MAX_MESSAGE - 1) + "…"
    }

    /**
     * SHA-256 of the kind, the site, the exception type and its top frames without line numbers
     * (QLauncher's own frames when there are any), so the same bug in two versions shares one.
     */
    fun fingerprint(kind: String, where: String?, exception: ReportException): String {
        val own = exception.frames.filter { it.startsWith(OWN_PACKAGE) }
        val frames = (own.ifEmpty { exception.frames }).take(FINGERPRINT_FRAMES).map { it.substringBefore('(') }
        val text = buildString {
            append(kind).append('\n').append(where.orEmpty()).append('\n').append(exception.type)
            frames.forEach { append('\n').append(it) }
        }
        return MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** ISO-8601 in UTC, as `Instant.toString` writes it: milliseconds only when there are any. */
    fun iso(epochMillis: Long): String {
        val pattern = if (epochMillis % 1000 == 0L) "yyyy-MM-dd'T'HH:mm:ss'Z'" else "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        return SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(epochMillis))
    }

    /**
     * Folds [pending] occurrences into events, one per fingerprint with its count and first and
     * last times, newest first, at most [MAX_EVENTS]. Returns the report and the occurrences it
     * covers (the rest stay for the next one).
     */
    fun build(pending: List<PendingEvent>, app: ReportApp, system: ReportSystem, now: Long): Pair<Report, List<PendingEvent>> {
        val groups = pending.groupBy { it.fingerprint }.values
            .sortedByDescending { group -> group.maxOf { it.at } }
            .take(MAX_EVENTS)
        val events = groups.map { group ->
            val latest = group.maxBy { it.at }
            ReportEvent(
                kind = latest.kind,
                where = latest.where,
                thread = latest.thread,
                firstAt = iso(group.minOf { it.at }),
                lastAt = iso(latest.at),
                count = group.size,
                fingerprint = latest.fingerprint,
                exception = latest.exception,
            )
        }
        return Report(sentAt = iso(now), app = app, system = system, events = events) to groups.flatten()
    }
}
