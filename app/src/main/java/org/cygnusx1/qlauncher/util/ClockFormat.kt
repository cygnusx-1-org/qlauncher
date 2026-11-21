/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.util

import android.content.Context
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The inline markup the clock format accepts. Nesting is allowed. */
enum class ClockMarkup(val delimiter: String) {
    Bold("**"),
    Italic("*"),
    Underline("__"),
    Strikethrough("~~"),
    Subscript("~"),
    Superscript("^"),
    HalfSize("--"),
    OneAndAHalfSize("++"),
    ;

    companion object {
        /** Longest delimiters first, so `**` is not read as two `*`. */
        internal val byLength: List<ClockMarkup> = entries.sortedByDescending { it.delimiter.length }
    }
}

/** A piece of format text and the markup that applies to it. */
data class MarkupRun(val text: String, val styles: Set<ClockMarkup>)

/**
 * Formats the status-bar clock: each line is a date pattern (`SimpleDateFormat` letters, `'…'`
 * for literal text) with inline markup, rendered into an [AnnotatedString].
 *
 * The pattern is formatted first and the markup read from the result (the markup characters are
 * not pattern letters, so they pass through; markup inside quotes works too). When the whole
 * pattern is not a valid date pattern, the markup is read from the pattern instead and each piece
 * is formatted on its own, a piece that is not valid shown as typed. A delimiter without a
 * partner is shown as text.
 *
 * @param systemTimePattern used when line 1 is empty; see [systemTimePattern].
 */
class ClockFormat(
    val systemTimePattern: String,
    private val locale: Locale = Locale.getDefault(),
    private val timeZone: TimeZone = TimeZone.getDefault(),
) {
    /** Line 1: [pattern], or the system's short time format when it is empty. */
    fun line1(pattern: String, timeMillis: Long): AnnotatedString =
        format(pattern.ifEmpty { systemTimePattern }, timeMillis)

    /** Line 2: null (no second line) when [pattern] is empty. */
    fun line2(pattern: String, timeMillis: Long): AnnotatedString? =
        if (pattern.isEmpty()) null else format(pattern, timeMillis)

    /** Formats one line. */
    fun format(pattern: String, timeMillis: Long): AnnotatedString = format(pattern, Date(timeMillis))

    /** Formats one line at [date]. */
    fun format(pattern: String, date: Date): AnnotatedString {
        val whole = tryFormat(pattern, date)
        val runs = if (whole != null) {
            parseMarkup(whole, respectQuotes = false)
        } else {
            parseMarkup(pattern).map { it.copy(text = formatPattern(it.text, date)) }
        }
        return buildAnnotatedString {
            for (run in runs) {
                val text = run.text
                if (text.isEmpty()) continue
                val style = spanStyle(run.styles)
                if (style == null) append(text) else withStyle(style) { append(text) }
            }
        }
    }

    /** Formats one piece of date pattern; invalid patterns come back unchanged. */
    fun formatPattern(pattern: String, date: Date): String = tryFormat(pattern, date) ?: pattern

    private fun tryFormat(pattern: String, date: Date): String? {
        if (pattern.isEmpty()) return ""
        return try {
            SimpleDateFormat(pattern, locale).apply { timeZone = this@ClockFormat.timeZone }.format(date)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * True when the formatted text can change within a minute (seconds or milliseconds in the
     * pattern), so the clock knows how often to tick.
     */
    fun needsSeconds(vararg patterns: String): Boolean = patterns.any { p ->
        parseMarkup(p.ifEmpty { systemTimePattern }).any { run -> unquoted(run.text).any { it == 's' || it == 'S' } }
    }

    companion object {
        /**
         * The system's short time pattern honouring the 12/24-hour setting, without the AM/PM
         * marker (as Projectivy shows it: `h:mm` or `HH:mm`).
         */
        fun systemTimePattern(context: Context, locale: Locale = Locale.getDefault()): String {
            val is24 = android.text.format.DateFormat.is24HourFormat(context)
            val best = android.text.format.DateFormat.getBestDateTimePattern(locale, if (is24) "Hm" else "hm")
            return stripDayPeriod(best).ifEmpty { if (is24) "HH:mm" else "h:mm" }
        }

        /** A [ClockFormat] for the device's locale, time zone and 12/24-hour setting. */
        fun forSystem(context: Context): ClockFormat = ClockFormat(systemTimePattern(context))

        /** Removes the AM/PM fields (`a`, `b`, `B`) and the spaces around them from a pattern. */
        fun stripDayPeriod(pattern: String): String {
            val out = StringBuilder()
            var quoted = false
            for (c in pattern) {
                if (c == '\'') quoted = !quoted
                if (!quoted && (c == 'a' || c == 'b' || c == 'B')) continue
                out.append(c)
            }
            return out.toString().replace(Regex("[\\s  ]+"), " ").trim()
        }

        /**
         * Splits [text] into runs; delimiters that have a partner become styles. With
         * [respectQuotes] (a pattern), delimiters inside `'…'` are text.
         */
        fun parseMarkup(text: String, respectQuotes: Boolean = true): List<MarkupRun> {
            // Tokenise.
            data class Token(val text: String, val markup: ClockMarkup?)
            val tokens = ArrayList<Token>()
            val buf = StringBuilder()
            var i = 0
            var quoted = false
            while (i < text.length) {
                val c = text[i]
                if (respectQuotes && c == '\'') quoted = !quoted
                val m = if (quoted) null else ClockMarkup.byLength.firstOrNull { text.startsWith(it.delimiter, i) }
                if (m == null) {
                    buf.append(c)
                    i++
                } else {
                    if (buf.isNotEmpty()) tokens += Token(buf.toString(), null).also { buf.clear() }
                    tokens += Token(m.delimiter, m)
                    i += m.delimiter.length
                }
            }
            if (buf.isNotEmpty()) tokens += Token(buf.toString(), null)

            // Pair the delimiters of each kind in order; an odd one out is plain text.
            val paired = BooleanArray(tokens.size)
            for (kind in ClockMarkup.entries) {
                val idx = tokens.indices.filter { tokens[it].markup == kind }
                for (k in 0 until idx.size / 2 * 2) paired[idx[k]] = true
            }

            // Walk, toggling styles.
            val runs = ArrayList<MarkupRun>()
            val active = LinkedHashSet<ClockMarkup>()
            val pending = StringBuilder()
            fun flush() {
                if (pending.isNotEmpty()) {
                    runs += MarkupRun(pending.toString(), active.toSet())
                    pending.clear()
                }
            }
            tokens.forEachIndexed { k, t ->
                val m = t.markup
                if (m != null && paired[k]) {
                    flush()
                    if (!active.remove(m)) active += m
                } else {
                    pending.append(t.text)
                }
            }
            flush()
            return runs
        }

        /** The span style for a set of markup, or null for plain text. */
        fun spanStyle(styles: Set<ClockMarkup>): SpanStyle? {
            if (styles.isEmpty()) return null
            var size = 1f
            if (ClockMarkup.HalfSize in styles) size *= 0.5f
            if (ClockMarkup.OneAndAHalfSize in styles) size *= 1.5f
            val decorations = buildList {
                if (ClockMarkup.Underline in styles) add(TextDecoration.Underline)
                if (ClockMarkup.Strikethrough in styles) add(TextDecoration.LineThrough)
            }
            val shift = when {
                ClockMarkup.Superscript in styles && ClockMarkup.Subscript !in styles -> BaselineShift.Superscript
                ClockMarkup.Subscript in styles && ClockMarkup.Superscript !in styles -> BaselineShift.Subscript
                else -> null
            }
            return SpanStyle(
                fontWeight = if (ClockMarkup.Bold in styles) FontWeight.Bold else null,
                fontStyle = if (ClockMarkup.Italic in styles) FontStyle.Italic else null,
                textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
                baselineShift = shift,
                fontSize = if (size == 1f) androidx.compose.ui.unit.TextUnit.Unspecified else size.em,
            )
        }

        private fun unquoted(pattern: String): String {
            val out = StringBuilder()
            var quoted = false
            for (c in pattern) {
                if (c == '\'') {
                    quoted = !quoted
                    continue
                }
                if (!quoted) out.append(c)
            }
            return out.toString()
        }
    }
}

/** One line of the clock-format help footer: what to type, and what it gives. */
data class ClockHelpLine(val source: String, val rendered: AnnotatedString)

/**
 * The help block at the end of the clock-format page: three sample date patterns rendered with a
 * given time, then one sample per markup.
 */
object ClockFormatHelp {
    val SAMPLE_PATTERNS: List<String> = listOf("d/MM/yy HH:mm", "E d MMM yyyy", "EEEE d MMMM")

    /** The pattern samples, rendered at [timeMillis]. */
    fun patternSamples(clock: ClockFormat, timeMillis: Long): List<ClockHelpLine> =
        SAMPLE_PATTERNS.map { ClockHelpLine(it, clock.format(it, timeMillis)) }

    /**
     * One sample per markup. [words] gives the (localised) sample word for each markup, for
     * example "bold" for [ClockMarkup.Bold].
     */
    fun markupSamples(words: (ClockMarkup) -> String): List<ClockHelpLine> = ClockMarkup.entries.map { m ->
        val word = words(m)
        val source = m.delimiter + word + m.delimiter
        val style = ClockFormat.spanStyle(setOf(m))
        ClockHelpLine(source, buildAnnotatedString { if (style != null) withStyle(style) { append(word) } else append(word) })
    }

    /** Every line of the footer: the pattern samples, then the markup samples. */
    fun lines(clock: ClockFormat, timeMillis: Long, words: (ClockMarkup) -> String): List<ClockHelpLine> =
        patternSamples(clock, timeMillis) + markupSamples(words)
}
