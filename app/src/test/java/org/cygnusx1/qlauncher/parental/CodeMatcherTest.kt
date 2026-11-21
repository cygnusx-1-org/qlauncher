/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.parental

import org.cygnusx1.qlauncher.parental.CodeMatcher.Result
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.SecureRandom

class CodeMatcherTest {
    private val random = SecureRandom()

    private fun seq(s: String) = ParentalToken.parse(s)!!
    private fun stored(s: String) = StoredCode.create(seq(s), random, iterations = 8)

    private fun CodeMatcher.type(s: String): List<Result> = seq(s).map { onToken(it) }

    @Test
    fun matchesTheMomentTheLastKeysAreTheCodeWithNoOk() {
        val m = CodeMatcher(stored("ULDR"), null)
        val results = m.type("ULDR")
        assertEquals(listOf(Result.Pending, Result.Pending, Result.Pending, Result.Matched), results)
    }

    @Test
    fun earlierWrongKeysDoNotMatter() {
        val m = CodeMatcher(stored("ULDR"), null)
        assertEquals(Result.Matched, m.type("DDULDR").last())
    }

    @Test
    fun wrongIsReportedOnceMoreThanTwiceTheLengthHasBeenEntered() {
        val m = CodeMatcher(stored("1234"), null)
        assertEquals(8, m.wrongAfter)
        val results = m.type("999999999")
        assertEquals(List(8) { Result.Pending } + Result.Wrong, results)
        assertEquals("the attempt starts over", 0, m.enteredCount)
        assertEquals(List(8) { Result.Pending }, m.type("99999999"))
        assertEquals(Result.Wrong, m.type("9").single())
    }

    @Test
    fun codeTypedAtTheLimitStillMatches() {
        val m = CodeMatcher(stored("1234"), null)
        assertEquals(Result.Matched, m.type("99991234").last())
    }

    @Test
    fun theRecoveryCodeLengthIsAlwaysAllowed() {
        val m = CodeMatcher(stored("12"), stored("UDLRUDLRUDLR"))
        assertEquals("not after 4 keys but after 12", 12, m.wrongAfter)
        assertEquals(Result.RecoveryMatched, m.type("UDLRUDLRUDLR").last())
    }

    @Test
    fun recoveryCodeCanBeTypedAsTheDigitsShown() {
        val m = CodeMatcher(stored("UU"), stored("UDLRUDLRUDLR"))
        val shownDigits = "123412341234"
        assertEquals(Result.RecoveryMatched, m.type(shownDigits).last())
    }

    @Test
    fun wrongAfterThirteenKeysWithARecoveryCode() {
        val m = CodeMatcher(stored("12"), stored("UDLRUDLRUDLR"))
        val results = m.type("9999999999999")
        assertEquals(List(12) { Result.Pending } + Result.Wrong, results)
    }
}
