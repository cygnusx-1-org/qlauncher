/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrEncoderTest {

    @Test
    fun `matrix size is 17 plus 4 per version`() {
        for (v in 1..15) {
            val m = QrEncoder.encode("x".encodeToByteArray(), v)
            assertEquals(17 + 4 * v, m.size)
            assertEquals(v, m.version)
        }
    }

    @Test
    fun `codeword counts match the level M table`() {
        // ISO/IEC 18004 table 9, level M: total and data codewords per version.
        val total = intArrayOf(26, 44, 70, 100, 134, 172, 196, 242, 292, 346, 404, 466, 532, 581, 655)
        val data = intArrayOf(16, 28, 44, 64, 86, 108, 124, 154, 182, 216, 254, 290, 334, 365, 415)
        for (v in 1..15) {
            assertEquals("total v$v", total[v - 1], QrEncoder.totalCodewords(v))
            assertEquals("data v$v", data[v - 1], QrEncoder.dataCodewords(v))
        }
    }

    @Test
    fun `smallest version that fits is chosen`() {
        // Byte mode, level M capacities: v1 14 bytes, v2 26, v9 180, v10 213, v15 412.
        assertEquals(1, QrEncoder.encode("a".repeat(14)).version)
        assertEquals(2, QrEncoder.encode("a".repeat(15)).version)
        assertEquals(9, QrEncoder.encode("a".repeat(180)).version)
        assertEquals(10, QrEncoder.encode("a".repeat(181)).version)
        assertEquals(15, QrEncoder.encode("a".repeat(412)).version)
    }

    @Test(expected = QrEncoder.TooLongException::class)
    fun `text beyond version 15 is refused`() {
        QrEncoder.encode("a".repeat(413))
    }

    @Test
    fun `finder patterns sit in three corners with light separators`() {
        val m = QrEncoder.encode("https://fonts.google.com/")
        val n = m.size
        for ((r0, c0) in listOf(0 to 0, 0 to n - 7, n - 7 to 0)) {
            for (dr in 0 until 7) {
                for (dc in 0 until 7) {
                    val ring = maxOf(kotlin.math.abs(dr - 3), kotlin.math.abs(dc - 3))
                    assertEquals("finder at ($r0,$c0) +($dr,$dc)", ring != 2, m[r0 + dr, c0 + dc])
                    assertTrue(m.isFinder(r0 + dr, c0 + dc))
                }
            }
        }
        // Separators.
        for (i in 0 until 8) {
            assertFalse(m[7, i]); assertFalse(m[i, 7])
            assertFalse(m[7, n - 1 - i]); assertFalse(m[i, n - 8])
            assertFalse(m[n - 8, i]); assertFalse(m[n - 1 - i, 7])
        }
        // Timing patterns and the dark module.
        for (i in 8 until n - 8) {
            assertEquals(i % 2 == 0, m[6, i])
            assertEquals(i % 2 == 0, m[i, 6])
        }
        assertTrue(m[n - 8, 8])
    }

    @Test
    fun `format bits match the published table for level M`() {
        // ISO/IEC 18004 table C.1, error correction level M, masks 0 to 7.
        val expected = listOf(
            "101010000010010", "101000100100101", "101111001111100", "101101101001011",
            "100010111111001", "100000011001110", "100111110010111", "100101010100000",
        )
        for (mask in 0..7) {
            assertEquals("mask $mask", expected[mask], QrEncoder.formatBits(mask).toString(2).padStart(15, '0'))
        }
    }

    @Test
    fun `format bits are written in both copies`() {
        val m = QrEncoder.encode("https://fonts.google.com/")
        val n = m.size
        val bits = QrEncoder.formatBits(m.mask)
        fun bit(i: Int) = ((bits ushr i) and 1) != 0
        val first = (0..5).map { m[it, 8] } + listOf(m[7, 8], m[8, 8], m[8, 7]) + (9..14).map { m[8, 14 - it] }
        val second = (0..7).map { m[8, n - 1 - it] } + (8..14).map { m[n - 15 + it, 8] }
        assertEquals((0..14).map(::bit), first)
        assertEquals((0..14).map(::bit), second)
    }

    @Test
    fun `version bits match the published table`() {
        // ISO/IEC 18004 table D.1.
        assertEquals(0x07C94, QrEncoder.versionBits(7))
        assertEquals(0x085BC, QrEncoder.versionBits(8))
        assertEquals(0x0A4D3, QrEncoder.versionBits(10))
        assertEquals(0x0F928, QrEncoder.versionBits(15))
    }

    /**
     * The URL Projectivy's Font page shows. Reference produced independently with ZXing 3.5.4's
     * encoder (`Encoder.encode(text, ErrorCorrectionLevel.M)`: version 2, mask 6), and the
     * rendered symbol decodes with zbarimg.
     */
    @Test
    fun `full matrix matches the reference`() {
        val expected = listOf(
            "#######.##.####...#######",
            "#.....#.##.#....#.#.....#",
            "#.###.#.#...###...#.###.#",
            "#.###.#.....##.##.#.###.#",
            "#.###.#.##.####.#.#.###.#",
            "#.....#....##.#...#.....#",
            "#######.#.#.#.#.#.#######",
            "..........##.####........",
            "#..######.....#.##..#.###",
            "#.#.##...#.#..##...#####.",
            "..########..#..##....#..#",
            ".#.#....#.#.#.##...#.####",
            "#...#.##.####.###.##....#",
            "###....###....###...#..#.",
            "##..###....##.####..#####",
            "#..###..##.#..##...#.##.#",
            "#.#.######..##..#####.##.",
            "........#####...#...#.##.",
            "#######.#..#..#.#.#.#...#",
            "#.....#.##.#.#..#...#..##",
            "#.###.#.#..##.#######....",
            "#.###.#.##.#####.##....##",
            "#.###.#..###.#.#....#####",
            "#.....#....#..##..###.###",
            "#######.#...#...###..#..#",
        )
        val m = QrEncoder.encode("https://fonts.google.com/")
        assertEquals(2, m.version)
        assertEquals(6, m.mask)
        assertEquals(expected, m.toRows())
    }

    @Test
    fun `utf8 text is encoded as bytes`() {
        val m = QrEncoder.encode("Größe 🔗")
        assertEquals(1, m.version) // 12 UTF-8 bytes fit version 1-M (14 bytes).
    }
}
