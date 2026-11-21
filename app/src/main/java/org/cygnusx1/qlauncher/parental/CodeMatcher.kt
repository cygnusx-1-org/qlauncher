/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.parental

/**
 * Checks a code as it is typed, with no OK needed: after every key the last *length* keys are
 * compared with the code (and the last [RecoveryCode.LENGTH] with the recovery code), so the
 * prompt opens the moment the right keys have been pressed, whatever came before them.
 *
 * Once more than twice the code's length has been entered without a match (and more than the
 * recovery code's length, so the recovery code can always be typed in full) the attempt is
 * reported [Result.Wrong] once and starts over.
 */
class CodeMatcher(private val code: StoredCode, private val recovery: StoredCode?) {
    enum class Result { Pending, Matched, RecoveryMatched, Wrong }

    private val window = maxOf(code.length, recovery?.length ?: 0)
    private val buffer = ArrayDeque<ParentalToken>(window)
    private var entered = 0

    /** Keys entered in the current attempt. */
    val enteredCount: Int get() = entered

    /** The number of keys after which an attempt without a match is wrong. */
    val wrongAfter: Int = maxOf(2 * code.length, recovery?.length ?: 0)

    fun onToken(token: ParentalToken): Result {
        buffer.addLast(token)
        while (buffer.size > window) buffer.removeFirst()
        entered++
        if (buffer.size >= code.length && code.matches(buffer.takeLast(code.length))) {
            reset()
            return Result.Matched
        }
        if (recovery != null && buffer.size >= recovery.length &&
            recovery.matches(RecoveryCode.normalize(buffer.takeLast(recovery.length)))
        ) {
            reset()
            return Result.RecoveryMatched
        }
        if (entered > wrongAfter) {
            reset()
            return Result.Wrong
        }
        return Result.Pending
    }

    fun reset() {
        buffer.clear()
        entered = 0
    }
}
