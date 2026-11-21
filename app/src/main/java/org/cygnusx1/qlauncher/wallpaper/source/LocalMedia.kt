/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.source

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cygnusx1.qlauncher.util.StorageRoots
import java.io.File

/** A picture, video or Lottie file on the device. [file] is its path when known. */
data class MediaItem(val uri: Uri, val file: File?, val name: String, val mimeType: String?, val modified: Long)

/** A folder holding [count] of the found media files; [label] is how it is shown. */
data class MediaFolder(val dir: File, val label: String, val count: Int)

/**
 * The pictures and videos on the device and on USB drives, found through MediaStore so that
 * choosing a wallpaper needs no file manager. Reading them needs the media permissions
 * ([permissions]); with *All files access* Lottie `.json` files in `Download` and `Documents` are
 * added too.
 */
object LocalMedia {
    /** The runtime permissions that let QLauncher see other apps' pictures and videos. */
    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** True when every picture and video can be listed ([permissions] granted, or *All files access*). */
    fun hasAccess(context: Context): Boolean =
        (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) ||
            permissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** Every picture and video MediaStore knows, plus Lottie files with *All files access*, newest first. */
    suspend fun files(context: Context): List<MediaItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<MediaItem>()
        query(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, out)
        query(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, out)
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            for (root in StorageRoots.all(context)) {
                for (dir in listOf(File(root, Environment.DIRECTORY_DOWNLOADS), File(root, Environment.DIRECTORY_DOCUMENTS))) {
                    dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) }?.forEach { f ->
                        out += MediaItem(Uri.fromFile(f), f, f.name, "application/json", f.lastModified())
                    }
                }
            }
        }
        out.distinctBy { it.uri }.sortedByDescending { it.modified }
    }

    /** The folders that hold [files], with how many each holds, by name. */
    fun folders(context: Context, files: List<MediaItem>): List<MediaFolder> {
        val roots = StorageRoots.all(context)
        return files.mapNotNull { it.file?.parentFile }
            .groupingBy { it.absolutePath }
            .eachCount()
            .map { (path, count) ->
                val dir = File(path)
                MediaFolder(dir, StorageRoots.describe(context, dir, roots) ?: path, count)
            }
            .sortedBy { it.label.lowercase() }
    }

    @Suppress("DEPRECATION") // DATA is the only path column before Android 10, and still filled after.
    private fun query(context: Context, collection: Uri, out: MutableList<MediaItem>) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATA,
        )
        runCatching {
            context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                    val path = c.getString(4)
                    val name = c.getString(1) ?: path?.substringAfterLast('/') ?: continue
                    out += MediaItem(uri, path?.let(::File), name, c.getString(2), c.getLong(3) * 1000L)
                }
            }
        }
    }
}
