/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppListBuilderTest {
    private fun a(pkg: String, cls: String = "$pkg.Main", label: String = pkg, isDefault: Boolean = false) =
        ScannedActivity(pkg, cls, label, isDefault = isDefault)

    @Test
    fun ownPackageIsLeftOut() {
        val l = AppListBuilder.build(listOf(a("me"), a("tv")), listOf(a("me"), a("phone")), ownPackage = "me")
        assertEquals(listOf("tv"), l.tv.map { it.packageName })
        assertEquals(listOf("phone"), l.mobile.map { it.packageName })
    }

    @Test
    fun aTvPackageIsNeverAMobileApp() {
        val l = AppListBuilder.build(
            tv = listOf(a("both", "both.TvMain")),
            mobile = listOf(a("both", "both.PhoneMain"), a("phone")),
            ownPackage = "me",
        )
        assertEquals(listOf("app:both/both.TvMain"), l.tv.map { it.key })
        assertEquals(listOf("phone"), l.mobile.map { it.packageName })
        assertTrue(l.tv.single().isTv)
        assertFalse(l.mobile.single().isTv)
        assertTrue(l.mobile.single().isMobile)
    }

    @Test
    fun onePerPackageUnlessDefault() {
        val l = AppListBuilder.build(
            tv = emptyList(),
            mobile = listOf(
                a("p", "p.A", "A"),
                a("p", "p.B", "B"),
                a("p", "p.C", "C", isDefault = true),
                a("p", "p.A", "A"),
            ),
            ownPackage = "me",
        )
        assertEquals(listOf("p.A", "p.C"), l.mobile.map { it.className })
    }

    @Test
    fun listsAreSortedByLabelAndAllIsTvThenMobile() {
        val l = AppListBuilder.build(
            tv = listOf(a("t2", label = "beta"), a("t1", label = "Alpha")),
            mobile = listOf(a("m2", label = "zulu"), a("m1", label = "Echo")),
            ownPackage = "me",
        )
        assertEquals(listOf("Alpha", "beta"), l.tv.map { it.label })
        assertEquals(listOf("Alpha", "beta", "Echo", "zulu"), l.all.map { it.label })
    }

    @Test
    fun titlePrefersTheCustomTitle() {
        val app = AppInfo("app:p/p.M", "p", "p.M", label = "Original", isTv = true)
        assertEquals("Original", app.title)
        assertEquals("Mine", app.copy(customTitle = "Mine").title)
        assertEquals("Original", app.copy(customTitle = "  ").title)
    }

    @Test
    fun relativeClassNamesBecomeFull() {
        val l = AppListBuilder.build(listOf(a("p", ".Tv")), emptyList(), "me")
        assertEquals("p.Tv", l.tv.single().className)
        assertEquals("app:p/p.Tv", l.tv.single().key)
    }
}
