/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.util

/**
 * A finished QR symbol: a square of dark and light modules, without the quiet zone.
 *
 * @property version 1 to 15.
 * @property mask the data mask (0 to 7) that was applied.
 */
class QrMatrix internal constructor(
    val version: Int,
    val mask: Int,
    private val dark: Array<BooleanArray>,
    private val function: Array<BooleanArray>,
) {
    /** Modules per side: 17 + 4 × version. */
    val size: Int get() = dark.size

    /** True when the module in [row], [col] is dark. */
    operator fun get(row: Int, col: Int): Boolean = dark[row][col]

    /** True for modules of the function patterns (finders, timing, alignment, format, version). */
    fun isFunction(row: Int, col: Int): Boolean = function[row][col]

    /** True inside one of the three 7 × 7 finder patterns. */
    fun isFinder(row: Int, col: Int): Boolean {
        val n = size
        return (row < 7 && col < 7) || (row < 7 && col >= n - 7) || (row >= n - 7 && col < 7)
    }

    /** One string per row, `#` for dark and `.` for light; handy in tests. */
    fun toRows(): List<String> = dark.map { r -> buildString { r.forEach { append(if (it) '#' else '.') } } }
}

/**
 * A small QR code encoder: byte mode (UTF-8), error correction level M, versions 1 to 15, all
 * eight masks scored with the four penalty rules of ISO/IEC 18004 and the lowest kept.
 */
object QrEncoder {
    const val MIN_VERSION = 1
    const val MAX_VERSION = 15

    /** Error-correction codewords per block, level M, index = version. */
    private val EC_PER_BLOCK = intArrayOf(0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24)

    /** Number of blocks, level M, index = version. */
    private val BLOCKS = intArrayOf(0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10)

    /** Alignment pattern centres per version. */
    private val ALIGNMENT = arrayOf(
        intArrayOf(),
        intArrayOf(),
        intArrayOf(6, 18),
        intArrayOf(6, 22),
        intArrayOf(6, 26),
        intArrayOf(6, 30),
        intArrayOf(6, 34),
        intArrayOf(6, 22, 38),
        intArrayOf(6, 24, 42),
        intArrayOf(6, 26, 46),
        intArrayOf(6, 28, 50),
        intArrayOf(6, 30, 54),
        intArrayOf(6, 32, 58),
        intArrayOf(6, 34, 62),
        intArrayOf(6, 26, 46, 66),
        intArrayOf(6, 26, 48, 70),
    )

    /** Format bits for level M are `00`. */
    private const val EC_LEVEL_M_BITS = 0

    class TooLongException(bytes: Int) : IllegalArgumentException("$bytes bytes do not fit in a version $MAX_VERSION-M QR code")

    /** Encodes [text] in the smallest version that holds it, choosing the best mask. */
    fun encode(text: String): QrMatrix = encode(text.encodeToByteArray())

    fun encode(data: ByteArray, forceMask: Int? = null): QrMatrix {
        val version = (MIN_VERSION..MAX_VERSION).firstOrNull { dataBitsNeeded(data.size, it) <= dataCodewords(it) * 8 }
            ?: throw TooLongException(data.size)
        return encode(data, version, forceMask)
    }

    /** Encodes into exactly [version]; fails when the data does not fit. */
    fun encode(data: ByteArray, version: Int, forceMask: Int? = null): QrMatrix {
        require(version in MIN_VERSION..MAX_VERSION) { "version $version" }
        require(forceMask == null || forceMask in 0..7) { "mask $forceMask" }
        val capacityBits = dataCodewords(version) * 8
        require(dataBitsNeeded(data.size, version) <= capacityBits) { "data too long for version $version" }

        val codewords = addErrorCorrection(dataCodewords(data, version), version)
        val base = Builder(version)
        base.drawFunctionPatterns()
        base.placeData(codewords)

        val mask = forceMask ?: (0..7).minBy { m ->
            val b = base.copy()
            b.applyMask(m)
            b.drawFormatBits(m)
            b.penalty()
        }
        base.applyMask(mask)
        base.drawFormatBits(mask)
        return QrMatrix(version, mask, base.modules, base.isFunction)
    }

    /** Raw modules available for data and error correction (excluding function patterns). */
    internal fun rawDataModules(version: Int): Int {
        var result = (16 * version + 128) * version + 64
        if (version >= 2) {
            val numAlign = version / 7 + 2
            result -= (25 * numAlign - 10) * numAlign - 55
            if (version >= 7) result -= 36
        }
        return result
    }

    internal fun totalCodewords(version: Int): Int = rawDataModules(version) / 8

    internal fun dataCodewords(version: Int): Int = totalCodewords(version) - EC_PER_BLOCK[version] * BLOCKS[version]

    private fun charCountBits(version: Int): Int = if (version <= 9) 8 else 16

    private fun dataBitsNeeded(bytes: Int, version: Int): Int {
        if (bytes >= (1 shl charCountBits(version))) return Int.MAX_VALUE
        return 4 + charCountBits(version) + 8 * bytes
    }

    /** Mode indicator, count, bytes, terminator and padding, as codewords. */
    internal fun dataCodewords(data: ByteArray, version: Int): ByteArray {
        val capacityBits = dataCodewords(version) * 8
        val bits = BitBuffer()
        bits.append(0b0100, 4)
        bits.append(data.size, charCountBits(version))
        data.forEach { bits.append(it.toInt() and 0xFF, 8) }
        bits.append(0, minOf(4, capacityBits - bits.size))
        bits.append(0, (8 - bits.size % 8) % 8)
        var pad = 0xEC
        while (bits.size < capacityBits) {
            bits.append(pad, 8)
            pad = pad xor 0xEC xor 0x11
        }
        return bits.toBytes()
    }

    /** Splits into blocks, adds Reed-Solomon codewords and interleaves. */
    internal fun addErrorCorrection(data: ByteArray, version: Int): ByteArray {
        val numBlocks = BLOCKS[version]
        val ecLen = EC_PER_BLOCK[version]
        val total = totalCodewords(version)
        val numShortBlocks = numBlocks - total % numBlocks
        val shortBlockLen = total / numBlocks
        val divisor = ReedSolomon.generator(ecLen)

        val blocks = ArrayList<ByteArray>(numBlocks)
        val ecBlocks = ArrayList<ByteArray>(numBlocks)
        var k = 0
        for (i in 0 until numBlocks) {
            val dataLen = shortBlockLen - ecLen + if (i < numShortBlocks) 0 else 1
            val dat = data.copyOfRange(k, k + dataLen)
            k += dataLen
            blocks += dat
            ecBlocks += ReedSolomon.remainder(dat, divisor)
        }

        val result = ByteArray(total)
        var p = 0
        val maxData = blocks.maxOf { it.size }
        for (i in 0 until maxData) {
            for (b in blocks) if (i < b.size) result[p++] = b[i]
        }
        for (i in 0 until ecLen) {
            for (e in ecBlocks) result[p++] = e[i]
        }
        check(p == total)
        return result
    }

    /** BCH-coded format bits: level M and [mask], XORed with the fixed pattern. */
    internal fun formatBits(mask: Int): Int {
        val data = (EC_LEVEL_M_BITS shl 3) or mask
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        return ((data shl 10) or rem) xor 0x5412
    }

    /** BCH-coded version bits for versions 7 and up. */
    internal fun versionBits(version: Int): Int {
        var rem = version
        repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
        return (version shl 12) or rem
    }

    private fun bit(x: Int, i: Int): Boolean = ((x ushr i) and 1) != 0

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val size: Int get() = bits.size

        fun append(value: Int, length: Int) {
            for (i in length - 1 downTo 0) bits += ((value ushr i) and 1) != 0
        }

        fun toBytes(): ByteArray {
            val out = ByteArray(bits.size / 8)
            bits.forEachIndexed { i, b -> if (b) out[i ushr 3] = (out[i ushr 3].toInt() or (0x80 ushr (i and 7))).toByte() }
            return out
        }
    }

    private object ReedSolomon {
        /** Generator polynomial of [degree] with roots α^0 .. α^(degree-1); leading 1 omitted. */
        fun generator(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in result.indices) {
                    result[j] = multiply(result[j], root)
                    if (j + 1 < result.size) result[j] = result[j] xor result[j + 1]
                }
                root = multiply(root, 0x02)
            }
            return result
        }

        fun remainder(data: ByteArray, divisor: IntArray): ByteArray {
            val result = IntArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() and 0xFF) xor result[0]
                for (i in 0 until result.size - 1) result[i] = result[i + 1]
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = result[i] xor multiply(divisor[i], factor)
            }
            return ByteArray(result.size) { result[it].toByte() }
        }

        /** Multiplication in GF(2^8) modulo x^8 + x^4 + x^3 + x^2 + 1. */
        fun multiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }
    }

    /** Mutable module grid used while building a symbol. */
    private class Builder(
        val version: Int,
        val modules: Array<BooleanArray> = Array(17 + 4 * version) { BooleanArray(17 + 4 * version) },
        val isFunction: Array<BooleanArray> = Array(17 + 4 * version) { BooleanArray(17 + 4 * version) },
    ) {
        val size = 17 + 4 * version

        fun copy() = Builder(version, Array(size) { modules[it].copyOf() }, Array(size) { isFunction[it].copyOf() })

        private fun setFunction(row: Int, col: Int, dark: Boolean) {
            modules[row][col] = dark
            isFunction[row][col] = true
        }

        fun drawFunctionPatterns() {
            for (i in 0 until size) {
                setFunction(6, i, i % 2 == 0)
                setFunction(i, 6, i % 2 == 0)
            }
            drawFinder(3, 3)
            drawFinder(3, size - 4)
            drawFinder(size - 4, 3)
            val align = ALIGNMENT[version]
            for (r in align.indices) {
                for (c in align.indices) {
                    val corner = (r == 0 && c == 0) || (r == 0 && c == align.lastIndex) || (r == align.lastIndex && c == 0)
                    if (!corner) drawAlignment(align[r], align[c])
                }
            }
            // Reserve the format areas (written for real once the mask is known) and the dark module.
            drawFormatBits(0)
            drawVersionBits()
        }

        private fun drawFinder(centerRow: Int, centerCol: Int) {
            for (dr in -4..4) {
                for (dc in -4..4) {
                    val r = centerRow + dr
                    val c = centerCol + dc
                    if (r !in 0 until size || c !in 0 until size) continue
                    val dist = maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc))
                    setFunction(r, c, dist != 2 && dist != 4)
                }
            }
        }

        private fun drawAlignment(centerRow: Int, centerCol: Int) {
            for (dr in -2..2) {
                for (dc in -2..2) {
                    setFunction(centerRow + dr, centerCol + dc, maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc)) != 1)
                }
            }
        }

        fun drawFormatBits(mask: Int) {
            val bits = formatBits(mask)
            // First copy, around the top-left finder.
            for (i in 0..5) setFunction(i, 8, bit(bits, i))
            setFunction(7, 8, bit(bits, 6))
            setFunction(8, 8, bit(bits, 7))
            setFunction(8, 7, bit(bits, 8))
            for (i in 9..14) setFunction(8, 14 - i, bit(bits, i))
            // Second copy, split between the top-right and bottom-left finders.
            for (i in 0..7) setFunction(8, size - 1 - i, bit(bits, i))
            for (i in 8..14) setFunction(size - 15 + i, 8, bit(bits, i))
            setFunction(size - 8, 8, true)
        }

        private fun drawVersionBits() {
            if (version < 7) return
            val bits = versionBits(version)
            for (i in 0 until 18) {
                val b = bit(bits, i)
                val a = size - 11 + i % 3
                val c = i / 3
                setFunction(c, a, b)
                setFunction(a, c, b)
            }
        }

        fun placeData(codewords: ByteArray) {
            var i = 0
            val totalBits = codewords.size * 8
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    for (j in 0..1) {
                        val col = right - j
                        val upward = ((right + 1) and 2) == 0
                        val row = if (upward) size - 1 - vert else vert
                        if (!isFunction[row][col] && i < totalBits) {
                            modules[row][col] = bit(codewords[i ushr 3].toInt(), 7 - (i and 7))
                            i++
                        }
                    }
                }
                right -= 2
            }
            check(i == totalBits)
        }

        fun applyMask(mask: Int) {
            for (row in 0 until size) {
                for (col in 0 until size) {
                    if (isFunction[row][col]) continue
                    val invert = when (mask) {
                        0 -> (row + col) % 2 == 0
                        1 -> row % 2 == 0
                        2 -> col % 3 == 0
                        3 -> (row + col) % 3 == 0
                        4 -> (row / 2 + col / 3) % 2 == 0
                        5 -> (row * col) % 2 + (row * col) % 3 == 0
                        6 -> ((row * col) % 2 + (row * col) % 3) % 2 == 0
                        7 -> ((row + col) % 2 + (row * col) % 3) % 2 == 0
                        else -> error("mask $mask")
                    }
                    if (invert) modules[row][col] = !modules[row][col]
                }
            }
        }

        fun penalty(): Int = QrPenalty.rules(modules).sum()
    }
}

/** The four mask penalty rules of ISO/IEC 18004 section 7.8.3, as ZXing interprets them. */
internal object QrPenalty {
    /** Penalties of rules 1 to 4 for [modules] (`[row][col]`, true = dark). */
    fun rules(modules: Array<BooleanArray>): IntArray {
        val size = modules.size
        var r1 = 0
        for (row in 0 until size) r1 += runPenalty(size) { modules[row][it] }
        for (col in 0 until size) r1 += runPenalty(size) { modules[it][col] }
        var r2 = 0
        for (row in 0 until size - 1) {
            for (col in 0 until size - 1) {
                val c = modules[row][col]
                if (c == modules[row][col + 1] && c == modules[row + 1][col] && c == modules[row + 1][col + 1]) r2 += 3
            }
        }
        var r3 = 0
        for (row in 0 until size) r3 += finderLikePenalty(size) { modules[row][it] }
        for (col in 0 until size) r3 += finderLikePenalty(size) { modules[it][col] }
        val dark = modules.sumOf { r -> r.count { it } }
        val total = size * size
        val r4 = kotlin.math.abs(dark * 2 - total) * 10 / total * 10
        return intArrayOf(r1, r2, r3, r4)
    }

    /** Rule 1: each run of five or more modules of one colour scores 3 plus its length over 5. */
    private fun runPenalty(size: Int, at: (Int) -> Boolean): Int {
        var result = 0
        var runColor = at(0)
        var runLen = 1
        for (i in 1 until size) {
            val c = at(i)
            if (c == runColor) {
                runLen++
            } else {
                if (runLen >= 5) result += 3 + (runLen - 5)
                runColor = c
                runLen = 1
            }
        }
        if (runLen >= 5) result += 3 + (runLen - 5)
        return result
    }

    /**
     * Rule 3: each `1011101` with four light modules before or after it, all inside the symbol,
     * scores 40. As in ZXing, the quiet zone does not count as light, so the finder patterns
     * themselves never score.
     */
    private fun finderLikePenalty(size: Int, at: (Int) -> Boolean): Int {
        fun light(from: Int, to: Int): Boolean = from >= 0 && to <= size && (from until to).none { at(it) }
        var result = 0
        for (start in 0 until size - 6) {
            val core = at(start) && !at(start + 1) && at(start + 2) && at(start + 3) && at(start + 4) &&
                !at(start + 5) && at(start + 6)
            if (core && (light(start - 4, start) || light(start + 7, start + 11))) result += 40
        }
        return result
    }
}
