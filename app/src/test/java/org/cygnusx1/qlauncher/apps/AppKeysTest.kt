/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.apps

import org.cygnusx1.qlauncher.data.ItemKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppKeysTest {
    @Test
    fun kindsAreRecognised() {
        assertEquals(ItemKind.App, AppKeys.kind(ItemKeys.app("a.b", "a.b.Main")))
        assertEquals(ItemKind.Shortcut, AppKeys.kind(ItemKeys.shortcut("a.b", "s1")))
        assertEquals(ItemKind.Input, AppKeys.kind(ItemKeys.input("x/HW5")))
        assertEquals(ItemKind.Program, AppKeys.kind(ItemKeys.program(7)))
        assertEquals(ItemKind.Action, AppKeys.kind(ItemKeys.action("all_apps")))
        assertEquals(ItemKind.Folder, AppKeys.kind(ItemKeys.folder(20)))
        assertEquals(ItemKind.Unknown, AppKeys.kind("something"))
    }

    @Test
    fun relativeClassNamesAreExpanded() {
        assertEquals("app:com.x/com.x.Main", AppKeys.app("com.x", ".Main"))
        assertEquals("app:com.x/org.y.Main", AppKeys.app("com.x", "org.y.Main"))
    }

    @Test
    fun shortcutIdsMayContainSlashes() {
        assertEquals("com.x" to "a/b/c", AppKeys.parseShortcut("shortcut:com.x/a/b/c"))
        assertNull(AppKeys.parseShortcut("shortcut:com.x/"))
        assertNull(AppKeys.parseShortcut("shortcut:/id"))
        assertNull(AppKeys.parseShortcut("app:com.x/id"))
    }

    @Test
    fun packageOfAppsAndShortcuts() {
        assertEquals("com.x", AppKeys.packageOf("app:com.x/com.x.Main"))
        assertEquals("com.y", AppKeys.packageOf("shortcut:com.y/id"))
        assertNull(AppKeys.packageOf("input:abc"))
    }

    @Test
    fun otherKeyBodies() {
        assertEquals("com.tv/.Hdmi/HW5", AppKeys.inputId("input:com.tv/.Hdmi/HW5"))
        assertNull(AppKeys.inputId("input:"))
        assertEquals(42L, AppKeys.programId("program:42"))
        assertNull(AppKeys.programId("program:x"))
        assertEquals("all_apps", AppKeys.actionId("action:all_apps"))
        assertEquals(23, AppKeys.folderCategoryId("folder:23"))
    }

    @Test
    fun appFilterComponentsAreLowerCasedAndNormalised() {
        assertEquals("com.abc.news/com.abc.news.mainactivity", AppKeys.appFilterComponent("ComponentInfo{com.abc.News/com.abc.News.MainActivity}"))
        assertEquals("com.x/com.x.main", AppKeys.appFilterComponent("ComponentInfo{com.x/.Main}"))
        assertEquals("com.x/com.x.main", AppKeys.appFilterComponent("  ComponentInfo{ com.x/com.x.Main }  "))
        assertEquals("com.x/com.x.main", AppKeys.appFilterComponent("com.x/com.x.Main"))
        assertEquals("com.x/com.x.main", AppKeys.appFilterComponent("componentinfo{com.x/com.x.Main}"))
    }

    @Test
    fun malformedAppFilterComponentsAreRejected() {
        assertNull(AppKeys.appFilterComponent("ComponentInfo{com.x}"))
        assertNull(AppKeys.appFilterComponent("ComponentInfo{com.x/com.x.Main"))
        assertNull(AppKeys.appFilterComponent("ComponentInfo{/com.x.Main}"))
        assertNull(AppKeys.appFilterComponent("ComponentInfo{com.x/}"))
        assertNull(AppKeys.appFilterComponent("ComponentInfo{com x/com.x.Main}"))
        assertNull(AppKeys.appFilterComponent(""))
        assertNull(AppKeys.appFilterComponent(":LAUNCHER_ACTION_APP_DRAWER"))
    }

    @Test
    fun autostartValuesRoundTrip() {
        val app = ItemKeys.app("com.x", "com.x.Main")
        assertEquals("com.x/com.x.Main", AppKeys.autostartValue(app))
        assertEquals(app, AppKeys.itemKeyOfAutostart("com.x/com.x.Main"))
        assertEquals("app:com.x/com.x.Main", AppKeys.itemKeyOfAutostart("com.x/.Main"))
        val input = ItemKeys.input("com.tv/.Hdmi/HW5")
        assertEquals(input, AppKeys.autostartValue(input))
        assertEquals(input, AppKeys.itemKeyOfAutostart(input))
        assertNull(AppKeys.autostartValue(ItemKeys.program(1)))
        assertNull(AppKeys.itemKeyOfAutostart(""))
        assertNull(AppKeys.itemKeyOfAutostart(AppKeys.AUTOSTART_SCREENSAVER))
        assertNull(AppKeys.itemKeyOfAutostart("nonsense"))
    }
}
