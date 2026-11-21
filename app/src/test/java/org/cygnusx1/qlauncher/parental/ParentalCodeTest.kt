/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.parental

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class ParentalCodeTest {
    private val random = SecureRandom()

    private fun seq(s: String) = ParentalToken.parse(s)!!

    @Test
    fun keyCodesMapToTokens() {
        assertEquals(ParentalToken.Up, ParentalToken.fromKeyCode(19))
        assertEquals(ParentalToken.Down, ParentalToken.fromKeyCode(20))
        assertEquals(ParentalToken.Left, ParentalToken.fromKeyCode(21))
        assertEquals(ParentalToken.Right, ParentalToken.fromKeyCode(22))
        assertEquals(ParentalToken.D0, ParentalToken.fromKeyCode(7)) // KEYCODE_0
        assertEquals(ParentalToken.D9, ParentalToken.fromKeyCode(16)) // KEYCODE_9
        assertEquals(ParentalToken.D5, ParentalToken.fromKeyCode(149)) // KEYCODE_NUMPAD_5
        assertNull("OK is not part of a code", ParentalToken.fromKeyCode(23))
        assertNull("BACK is not part of a code", ParentalToken.fromKeyCode(4))
    }

    @Test
    fun hashRoundTripsAndMatchesOnlyTheCode() {
        val code = seq("UDLR0942")
        val stored = StoredCode.decode(StoredCode.create(code, random).encode())
        assertNotNull(stored)
        stored!!
        assertEquals(8, stored.length)
        assertTrue(stored.matches(code))
        assertFalse(stored.matches(seq("UDLR0943")))
        assertFalse(stored.matches(seq("DULR0942")))
        assertFalse("length must match", stored.matches(seq("UDLR094")))
    }

    @Test
    fun storedTextHoldsTheLengthButNeverTheSequence() {
        val encoded = StoredCode.create(seq("UUDDLRLR12"), random).encode()
        assertTrue(encoded.startsWith("v1:10:"))
        assertFalse(encoded.contains("UUDDLRLR12"))
        assertEquals(5, encoded.split(':').size)
    }

    @Test
    fun sameCodeIsSaltedDifferentlyEachTime() {
        val code = seq("1234")
        val a = StoredCode.create(code, random).encode()
        val b = StoredCode.create(code, random).encode()
        assertNotEquals(a, b)
        assertTrue(StoredCode.decode(a)!!.matches(code))
        assertTrue(StoredCode.decode(b)!!.matches(code))
    }

    @Test
    fun damagedOrUnknownTextIsNoCode() {
        assertNull(StoredCode.decode(""))
        assertNull(StoredCode.decode("UDLR"))
        assertNull(StoredCode.decode("v2:4:1024:AAAA:AAAA"))
        assertNull(StoredCode.decode("v1:0:1024:AAAA:AAAA"))
        assertNull(StoredCode.decode("v1:4:1024:!!!!:AAAA"))
        assertNull("hash of the wrong size", StoredCode.decode("v1:4:1024:AAAAAAAAAAAAAAAAAAAAAA:AAAA"))
    }

    @Test
    fun recoveryCodeIsTwelveArrowsShownAsDigitsOneToFour() {
        val r = RecoveryCode.generate(random)
        assertEquals(12, r.size)
        assertTrue(r.all { it.isArrow })
        val shown = RecoveryCode.display(seq("UDLRUUDDLLRR"))
        assertEquals("1234 1122 3344", shown)
    }

    @Test
    fun recoveryDigitsTypedOnAKeypadCountAsArrows() {
        assertEquals(seq("UDLR"), RecoveryCode.normalize(seq("1234")))
        assertEquals(seq("U5R0"), RecoveryCode.normalize(seq("1540")))
    }

    @Test
    fun storedTextIsUnpaddedStandardBase64AndPaddedTextStillReads() {
        // 0xFB bytes encode to "+/v7": both characters outside the URL-safe alphabet.
        val fixed = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) = bytes.fill(0xFB.toByte())
        }
        val code = StoredCode.create(seq("UDLR0942"), fixed)
        val parts = code.encode().split(':')
        val jdk = java.util.Base64.getEncoder()
        val salt = ByteArray(StoredCode.SALT_BYTES) { 0xFB.toByte() }
        assertEquals(jdk.withoutPadding().encodeToString(salt), parts[3])
        val hash = java.util.Base64.getDecoder().decode(parts[4])
        assertEquals(jdk.withoutPadding().encodeToString(hash), parts[4])

        val padded = (parts.take(3) + jdk.encodeToString(salt) + jdk.encodeToString(hash)).joinToString(":")
        assertTrue(StoredCode.decode(padded)!!.matches(seq("UDLR0942")))
    }
}
