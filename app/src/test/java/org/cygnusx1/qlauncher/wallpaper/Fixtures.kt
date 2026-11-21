/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper

/** Reads a JSON fixture from the test resources of this package. */
object Fixtures {
    fun read(name: String): String =
        requireNotNull(Fixtures::class.java.getResourceAsStream("/org/cygnusx1/qlauncher/wallpaper/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }
}
