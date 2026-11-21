/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.parental

import android.view.KeyEvent
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.io.encoding.Base64

/** One key of a parental code: a D-pad arrow or a digit. */
enum class ParentalToken(val symbol: Char) {
    Up('U'), Down('D'), Left('L'), Right('R'),
    D0('0'), D1('1'), D2('2'), D3('3'), D4('4'), D5('5'), D6('6'), D7('7'), D8('8'), D9('9');

    val isArrow: Boolean get() = ordinal <= Right.ordinal

    companion object {
        private val bySymbol = entries.associateBy { it.symbol }

        fun of(symbol: Char): ParentalToken? = bySymbol[symbol]

        /** The token a remote key enters, or null for keys that are not part of a code. */
        fun fromKeyCode(keyCode: Int): ParentalToken? = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> Up
            KeyEvent.KEYCODE_DPAD_DOWN -> Down
            KeyEvent.KEYCODE_DPAD_LEFT -> Left
            KeyEvent.KEYCODE_DPAD_RIGHT -> Right
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> digit(keyCode - KeyEvent.KEYCODE_0)
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> digit(keyCode - KeyEvent.KEYCODE_NUMPAD_0)
            else -> null
        }

        fun digit(d: Int): ParentalToken = entries[D0.ordinal + d]

        fun parse(sequence: String): List<ParentalToken>? = sequence.map { of(it) ?: return null }
    }
}

fun List<ParentalToken>.asSequenceString(): String = joinToString("") { it.symbol.toString() }

/**
 * A stored code: a salted, iterated SHA-256 hash plus the code's length, never the sequence.
 *
 * Text format, version 1: `v1:<length>:<iterations>:<salt, base64>:<hash, base64>`.
 * The hash is `h₀ = SHA-256(salt ‖ sequence)`, `hᵢ = SHA-256(salt ‖ hᵢ₋₁)`, with the sequence
 * written as the token symbols (`U`, `D`, `L`, `R`, `0`–`9`) in UTF-8.
 */
class StoredCode private constructor(
    val length: Int,
    val iterations: Int,
    private val salt: ByteArray,
    private val hash: ByteArray,
) {
    /** True when [tokens] (exactly [length] of them) is this code. */
    fun matches(tokens: List<ParentalToken>): Boolean {
        if (tokens.size != length) return false
        return MessageDigest.isEqual(hash, digest(salt, tokens, iterations))
    }

    fun encode(): String = "$VERSION:$length:$iterations:${BASE64.encode(salt)}:${BASE64.encode(hash)}"

    override fun toString(): String = "StoredCode(length=$length)"

    companion object {
        const val VERSION = "v1"
        const val DEFAULT_ITERATIONS = 1024
        const val SALT_BYTES = 16

        /** Standard alphabet, written without padding; read with or without it. */
        private val BASE64 = Base64.Default.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

        /** Hashes [tokens] with a fresh random salt. */
        fun create(tokens: List<ParentalToken>, random: SecureRandom, iterations: Int = DEFAULT_ITERATIONS): StoredCode {
            require(tokens.isNotEmpty()) { "A code needs at least one key" }
            require(iterations >= 1)
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            return StoredCode(tokens.size, iterations, salt, digest(salt, tokens, iterations))
        }

        /** Reads the stored text; null when it is empty, of an unknown version or damaged. */
        fun decode(text: String): StoredCode? {
            if (text.isBlank()) return null
            val parts = text.split(':')
            if (parts.size != 5 || parts[0] != VERSION) return null
            val length = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val iterations = parts[2].toIntOrNull()?.takeIf { it in 1..1_000_000 } ?: return null
            return try {
                val salt = BASE64.decode(parts[3])
                val hash = BASE64.decode(parts[4])
                if (salt.isEmpty() || hash.size != 32) null else StoredCode(length, iterations, salt, hash)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun digest(salt: ByteArray, tokens: List<ParentalToken>, iterations: Int): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(salt)
            var h = md.digest(tokens.asSequenceString().toByteArray(Charsets.UTF_8))
            repeat(iterations - 1) {
                md.update(salt)
                h = md.digest(h)
            }
            return h
        }
    }
}

/**
 * The emergency recovery code: [LENGTH] random arrows, generated when a code is first set. It is
 * entered with the arrows like the code itself, and shown to the user as a number with one digit
 * per arrow (1 = up, 2 = down, 3 = left, 4 = right). On a remote with digit keys the number can be
 * typed as it is shown.
 */
object RecoveryCode {
    const val LENGTH = 12

    fun generate(random: SecureRandom): List<ParentalToken> = List(LENGTH) { ParentalToken.entries[random.nextInt(4)] }

    /** The number shown to the user, in groups of four: `1324 4123 2241`. */
    fun display(tokens: List<ParentalToken>): String =
        tokens.map { digitFor(it) }.chunked(4).joinToString(" ") { it.joinToString("") }

    /** Digits 1–4 typed on a numeric keypad stand for the arrows they label. */
    fun normalize(tokens: List<ParentalToken>): List<ParentalToken> = tokens.map {
        when (it) {
            ParentalToken.D1 -> ParentalToken.Up
            ParentalToken.D2 -> ParentalToken.Down
            ParentalToken.D3 -> ParentalToken.Left
            ParentalToken.D4 -> ParentalToken.Right
            else -> it
        }
    }

    private fun digitFor(token: ParentalToken): Char = when (token) {
        ParentalToken.Up -> '1'
        ParentalToken.Down -> '2'
        ParentalToken.Left -> '3'
        ParentalToken.Right -> '4'
        else -> token.symbol
    }
}
