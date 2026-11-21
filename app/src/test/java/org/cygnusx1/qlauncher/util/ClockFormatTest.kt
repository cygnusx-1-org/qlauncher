/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.util

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import org.cygnusx1.qlauncher.util.ClockMarkup.Bold
import org.cygnusx1.qlauncher.util.ClockMarkup.HalfSize
import org.cygnusx1.qlauncher.util.ClockMarkup.Italic
import org.cygnusx1.qlauncher.util.ClockMarkup.Strikethrough
import org.cygnusx1.qlauncher.util.ClockMarkup.Subscript
import org.cygnusx1.qlauncher.util.ClockMarkup.Underline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class ClockFormatTest {
    /** 2026-11-19 04:18:07 UTC, a Thursday (the capture of the clock page). */
    private val time = 1_795_061_887_000L

    private val clock12 = ClockFormat("h:mm", Locale.US, TimeZone.getTimeZone("UTC"))
    private val clock24 = ClockFormat("HH:mm", Locale.US, TimeZone.getTimeZone("UTC"))

    /** The text of each span with its style. */
    private fun AnnotatedString.styled(): List<Pair<String, SpanStyle>> = spanStyles.map { text.substring(it.start, it.end) to it.item }

    @Test
    fun `empty line 1 is the system short time, empty line 2 is no line`() {
        assertEquals("4:18", clock12.line1("", time).text)
        assertEquals("04:18", clock24.line1("", time).text)
        assertNull(clock12.line2("", time))
        assertEquals("Thursday", clock12.line2("EEEE", time)!!.text)
    }

    @Test
    fun `the help samples render with the given time`() {
        val lines = ClockFormatHelp.patternSamples(clock12, time)
        assertEquals(listOf("d/MM/yy HH:mm", "E d MMM yyyy", "EEEE d MMMM"), lines.map { it.source })
        assertEquals(listOf("19/11/26 04:18", "Thu 19 Nov 2026", "Thursday 19 November"), lines.map { it.rendered.text })
    }

    @Test
    fun `one help sample per markup`() {
        val lines = ClockFormatHelp.markupSamples { it.name.lowercase() }
        assertEquals(
            listOf("**bold**", "*italic*", "__underline__", "~~strikethrough~~", "~subscript~", "^superscript^", "--halfsize--", "++oneandahalfsize++"),
            lines.map { it.source },
        )
        assertEquals("bold", lines[0].rendered.text)
        assertEquals(FontWeight.Bold, lines[0].rendered.spanStyles.single().item.fontWeight)
        assertEquals(0.5f.em, lines[6].rendered.spanStyles.single().item.fontSize)
        assertEquals(1.5f.em, lines[7].rendered.spanStyles.single().item.fontSize)
    }

    @Test
    fun `markup styles the formatted text`() {
        val s = clock24.format("HH**mm**", time)
        assertEquals("0418", s.text)
        assertEquals(listOf("18" to SpanStyle(fontWeight = FontWeight.Bold)), s.styled())

        val date = clock24.format("--E d MMM--", time)
        assertEquals("Thu 19 Nov", date.text)
        assertEquals(0.5f.em, date.spanStyles.single().item.fontSize)
    }

    @Test
    fun `each delimiter maps to its style`() {
        fun style(p: String) = ClockFormat.parseMarkup(p).single { it.text == "x" }.styles
        assertEquals(setOf(Bold), style("**x**"))
        assertEquals(setOf(Italic), style("*x*"))
        assertEquals(setOf(Underline), style("__x__"))
        assertEquals(setOf(Strikethrough), style("~~x~~"))
        assertEquals(setOf(Subscript), style("~x~"))
        assertEquals(setOf(ClockMarkup.Superscript), style("^x^"))
        assertEquals(setOf(HalfSize), style("--x--"))
        assertEquals(setOf(ClockMarkup.OneAndAHalfSize), style("++x++"))

        assertEquals(BaselineShift.Superscript, ClockFormat.spanStyle(setOf(ClockMarkup.Superscript))!!.baselineShift)
        assertEquals(BaselineShift.Subscript, ClockFormat.spanStyle(setOf(Subscript))!!.baselineShift)
        assertEquals(TextDecoration.LineThrough, ClockFormat.spanStyle(setOf(Strikethrough))!!.textDecoration)
        assertEquals(FontStyle.Italic, ClockFormat.spanStyle(setOf(Italic))!!.fontStyle)
    }

    @Test
    fun `markup nests and combines`() {
        val runs = ClockFormat.parseMarkup("**a *b* c**")
        assertEquals(
            listOf(MarkupRun("a ", setOf(Bold)), MarkupRun("b", setOf(Bold, Italic)), MarkupRun(" c", setOf(Bold))),
            runs,
        )
        val both = ClockFormat.spanStyle(setOf(Underline, Strikethrough, HalfSize, ClockMarkup.OneAndAHalfSize))!!
        assertEquals(TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough)), both.textDecoration)
        assertEquals(0.75f.em, both.fontSize)

        val s = clock24.format("__HH ~~mm~~__", time)
        assertEquals("04 18", s.text)
        assertTrue(s.styled().any { (t, st) -> t == "18" && st.textDecoration == TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough)) })
    }

    @Test
    fun `unpaired delimiters and quoted text stay as typed`() {
        assertEquals(listOf(MarkupRun("a*b", emptySet())), ClockFormat.parseMarkup("a*b"))
        assertEquals("04:18 *", clock24.format("HH:mm *", time).text)
        assertEquals("**04", clock24.format("'**'HH", time).text)
        assertEquals("04 h", clock24.format("HH 'h'", time).text)
    }

    @Test
    fun `quoted markup still styles, as the settings help block writes it`() {
        val s = clock24.format("'**bold**'", time)
        assertEquals("bold", s.text)
        assertEquals(FontWeight.Bold, s.spanStyles.single().item.fontWeight)
        val sub = clock24.format("'x~subscript~'", time)
        assertEquals("xsubscript", sub.text)
        assertEquals(listOf("subscript" to SpanStyle(baselineShift = BaselineShift.Subscript)), sub.styled())
    }

    @Test
    fun `a piece that is not a date pattern is shown as typed`() {
        assertEquals("Today", clock24.formatPattern("Today", java.util.Date(time)))
        // The pattern as a whole is invalid: markup still applies, and valid pieces are formatted.
        val s = clock24.format("Today **HH**", time)
        assertEquals("Today 04", s.text)
        assertEquals(listOf("04" to SpanStyle(fontWeight = FontWeight.Bold)), s.styled())
    }

    @Test
    fun `day period markers are stripped from the system pattern`() {
        assertEquals("h:mm", ClockFormat.stripDayPeriod("h:mm a"))
        assertEquals("h:mm", ClockFormat.stripDayPeriod("a h:mm"))
        assertEquals("h:mm", ClockFormat.stripDayPeriod("h:mm a"))
        assertEquals("HH:mm", ClockFormat.stripDayPeriod("HH:mm"))
        assertEquals("H 'at' mm", ClockFormat.stripDayPeriod("H 'at' mm"))
    }

    @Test
    fun `seconds in a pattern make the clock tick every second`() {
        assertTrue(clock24.needsSeconds("HH:mm:ss"))
        assertFalse(clock24.needsSeconds("HH:mm", ""))
        assertFalse(clock24.needsSeconds("'secs' HH"))
    }
}
