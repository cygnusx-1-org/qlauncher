/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings.backup

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.util.StorageRoots
import java.io.File
import java.io.IOException

/** A backup or theme file QLauncher found on the device; [location] is shown with it. */
data class BackupFile(val name: String, val uri: Uri, val location: String, val modified: Long, val size: Long)

/**
 * Backups in shared storage, so that no file manager is needed, as Electric Eel does it: exports
 * are written to `Documents/QLauncher/` (through MediaStore from Android 10, as a plain file
 * before), and imports are picked from a list of the backup files QLauncher can see.
 *
 * QLauncher always sees the exports it wrote itself. Files from other apps or devices (a backup
 * copied over, Projectivy's exports, a USB drive) need *All files access* from Android 11, and the
 * storage permission before.
 */
object PublicBackups {
    const val FOLDER = "QLauncher"

    /** `Documents/QLauncher`, as shown to the user. */
    val shownFolder: String get() = Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER

    /** True when saving needs the storage permission first (Android 9 and older). */
    fun needsWritePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 && !granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    /**
     * Copies [file] into `Documents/QLauncher` under its own name and returns the path to show
     * (`Documents/QLauncher/<name>`).
     *
     * @throws IOException when it cannot be written.
     */
    suspend fun save(context: Context, file: File): String = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 29) saveToMediaStore(context, file) else saveToFolder(file)
    }

    @RequiresApi(29)
    private fun saveToMediaStore(context: Context, file: File): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$shownFolder/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection(), values) ?: throw IOException("MediaStore refused $shownFolder/${file.name}")
        try {
            val out = resolver.openOutputStream(uri, "w") ?: throw IOException("cannot open $uri")
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e as? IOException ?: IOException(e)
        }
        // MediaStore renames on a clash ("name (1).qlbackup"); show the name it kept.
        val kept = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        return "$shownFolder/${kept ?: file.name}"
    }

    @Suppress("DEPRECATION") // The public folder path is the only way before Android 10.
    private fun saveToFolder(file: File): String {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), FOLDER)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        file.copyTo(File(dir, file.name), overwrite = true)
        return "$shownFolder/${file.name}"
    }

    /** True when files from other apps, devices and USB drives can be listed. */
    fun hasBroadAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT == 29 -> Environment.isExternalStorageLegacy() && granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Grants *All files access* where the TV's settings have no page for it (the stock emulator). */
    fun allFilesAccessAdbCommand(context: Context): String =
        "adb shell appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow"

    /**
     * The *All files access* page for QLauncher (Android 11 and up), or null when the device has
     * none; before Android 11 the storage permission is asked for instead.
     */
    fun allFilesAccessIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < 30) return null
        val pm = context.packageManager
        val own = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", context.packageName, null))
        if (own.resolveActivity(pm) != null) return own
        val list = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        return if (list.resolveActivity(pm) != null) list else null
    }

    /**
     * The backup files whose extension starts with one of [extensions] (`qlbackup`, `plbackup`, …),
     * newest first: QLauncher's own folder, its exports in `Documents/QLauncher`, and with broad
     * access also `Documents`, `Download` and the top of every storage volume (USB drives too).
     * Folders are not searched below that.
     */
    suspend fun find(context: Context, exportDir: File, extensions: Set<String>): List<BackupFile> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, BackupFile>()
        fun matches(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext.isNotEmpty() && extensions.any { ext.startsWith(it) }
        }
        fun addDir(dir: File, location: String) {
            dir.listFiles()?.forEach { f ->
                if (f.isFile && matches(f.name)) {
                    found.getOrPut(f.absolutePath) { BackupFile(f.name, Uri.fromFile(f), location, f.lastModified(), f.length()) }
                }
            }
        }

        addDir(exportDir, context.getString(R.string.backup_files_app_folder))
        val primary = StorageRoots.primary()
        val roots = StorageRoots.all(context)
        for (root in roots) {
            for (dir in listOf(File(root, shownFolder), File(root, Environment.DIRECTORY_DOCUMENTS), File(root, Environment.DIRECTORY_DOWNLOADS), root)) {
                addDir(dir, StorageRoots.describe(context, dir, roots) ?: dir.absolutePath)
            }
        }
        if (Build.VERSION.SDK_INT >= 29 && primary != null) {
            // MediaStore always lists what this install wrote, even where the folder cannot be read.
            runCatching { addOwnExports(context, File(primary, shownFolder), ::matches, found) }
        }
        found.values.sortedByDescending { it.modified }
    }

    @RequiresApi(29)
    private fun addOwnExports(context: Context, dir: File, matches: (String) -> Boolean, found: MutableMap<String, BackupFile>) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
        )
        context.contentResolver.query(
            collection(),
            projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf("$shownFolder/"),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (!matches(name)) continue
                val key = File(dir, name).absolutePath
                if (key in found) continue
                found[key] = BackupFile(name, ContentUris.withAppendedId(collection(), c.getLong(0)), shownFolder, c.getLong(2) * 1000L, c.getLong(3))
            }
        }
    }

    private fun collection(): Uri = MediaStore.Files.getContentUri("external")

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private const val MIME_TYPE = "application/octet-stream"
}
