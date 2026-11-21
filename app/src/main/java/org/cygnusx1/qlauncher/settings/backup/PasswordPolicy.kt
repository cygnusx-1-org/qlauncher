/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings.backup

import kotlin.math.log2

/** How hard a password is to guess offline (WinZip AES uses only 1000 PBKDF2 rounds). */
enum class PasswordStrength { TooShort, Weak, Fair, Strong }

/** Why an export password is refused. */
enum class PasswordProblem { TooShort }

/** The export password rules: at least eight characters. */
object PasswordPolicy {
    /** The problem with [password], or null when it can be used. */
    fun check(password: String): PasswordProblem? =
        if (password.length < BackupFormat.MIN_PASSWORD_LENGTH) PasswordProblem.TooShort else null

    /**
     * A rough strength hint from length and character variety: the bits of a random password of
     * the same length drawn from the classes it uses, discounted for repeats.
     */
    fun strength(password: String): PasswordStrength {
        if (password.length < BackupFormat.MIN_PASSWORD_LENGTH) return PasswordStrength.TooShort
        var pool = 0
        if (password.any { it.isLowerCase() }) pool += 26
        if (password.any { it.isUpperCase() }) pool += 26
        if (password.any { it.isDigit() }) pool += 10
        if (password.any { !it.isLetterOrDigit() }) pool += 33
        val distinct = password.toSet().size
        val effectiveLength = (password.length + distinct) / 2.0
        val bits = effectiveLength * log2(pool.coerceAtLeast(2).toDouble())
        return when {
            bits >= 70 -> PasswordStrength.Strong
            bits >= 48 -> PasswordStrength.Fair
            else -> PasswordStrength.Weak
        }
    }
}
