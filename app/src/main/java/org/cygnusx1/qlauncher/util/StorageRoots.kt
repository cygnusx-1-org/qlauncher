/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.util

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/** The mounted storage volumes, and how a folder on them is named to the user. */
object StorageRoots {
    /** The top folder of every mounted volume: internal storage first, then USB drives and cards. */
    fun all(context: Context): List<File> =
        context.getExternalFilesDirs(null).mapNotNull { dir ->
            dir?.absolutePath?.substringBefore("/Android/data/", "")?.takeIf { it.isNotEmpty() }?.let(::File)
        }.distinctBy { it.absolutePath }

    /** Internal shared storage (`/storage/emulated/0`). */
    @Suppress("DEPRECATION") // Only the path is used, to tell the volumes apart.
    fun primary(): File? = Environment.getExternalStorageDirectory()

    /**
     * [dir] as the user knows it: `Pictures/Holiday` on internal storage, `<volume>/Wallpapers` on
     * a USB drive, the volume's name for its top folder. Null when [dir] is on no known volume.
     */
    fun describe(context: Context, dir: File, roots: List<File> = all(context)): String? {
        val path = dir.absolutePath
        val root = roots.filter { path == it.absolutePath || path.startsWith(it.absolutePath + "/") }
            .maxByOrNull { it.absolutePath.length } ?: return null
        val relative = path.removePrefix(root.absolutePath).trim('/')
        val isPrimary = root.absolutePath == primary()?.absolutePath
        val volume = volumeName(context, root)
        return when {
            relative.isEmpty() -> volume
            isPrimary -> relative
            else -> "$volume/$relative"
        }
    }

    private fun volumeName(context: Context, root: File): String =
        runCatching { context.getSystemService(StorageManager::class.java)?.getStorageVolume(root)?.getDescription(context) }
            .getOrNull() ?: root.name
}
