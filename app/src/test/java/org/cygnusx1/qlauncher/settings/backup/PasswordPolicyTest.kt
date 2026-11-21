/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PasswordPolicyTest {
    @Test
    fun `at least eight characters`() {
        assertEquals(PasswordProblem.TooShort, PasswordPolicy.check("1234567"))
        assertNull(PasswordPolicy.check("12345678"))
    }

    @Test
    fun `strength grows with length and variety`() {
        assertEquals(PasswordStrength.TooShort, PasswordPolicy.strength("abc"))
        assertEquals(PasswordStrength.Weak, PasswordPolicy.strength("password"))
        assertEquals(PasswordStrength.Weak, PasswordPolicy.strength("12345678"))
        assertEquals(PasswordStrength.Weak, PasswordPolicy.strength("aaaaaaaaaaaa"))
        assertEquals(PasswordStrength.Fair, PasswordPolicy.strength("Tr0ub4dor&3"))
        assertEquals(PasswordStrength.Strong, PasswordPolicy.strength("correct horse battery staple"))
    }
}
