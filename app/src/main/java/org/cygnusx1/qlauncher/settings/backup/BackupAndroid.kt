/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings.backup

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** The folders of this app: exports go to the external `files/backups/` when it is available. */
fun BackupPaths(context: Context): BackupPaths {
    val app = context.applicationContext
    return BackupPaths(
        filesDir = app.filesDir,
        cacheDir = app.cacheDir,
        packageName = app.packageName,
        exportDir = { File(app.getExternalFilesDir(null) ?: app.filesDir, "backups") },
    )
}

/**
 * Copies [uri] into the cache and recognises it: a QLauncher `.qlbackup` / `.qltheme`, or a
 * Projectivy `.plbackup` / `.pltheme`.
 *
 * @throws ImportException.NotABackup when it is none of them; [ImportException.Damaged] when it
 *   cannot be read.
 */
suspend fun BackupManager.open(context: Context, uri: Uri): ImportSession = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val name = displayName(resolver, uri)
    val dir = File(stagingRoot, UUID.randomUUID().toString()).apply { mkdirs() }
    val file = File(dir, "incoming")
    try {
        val input = resolver.openInputStream(uri) ?: throw ImportException.NotABackup()
        input.use { i -> file.outputStream().use { i.copyTo(it) } }
    } catch (e: IOException) {
        dir.deleteRecursively()
        throw ImportException.Damaged(e)
    } catch (e: SecurityException) {
        dir.deleteRecursively()
        throw ImportException.Damaged(e)
    }
    openStaged(name, file, dir)
}

/** An `ACTION_SEND` chooser for *Share with another app*, through the `${applicationId}.files` provider. */
fun BackupManager.shareIntent(context: Context, file: File, chooserTitle: CharSequence): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/octet-stream")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, file.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

private fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
    if (uri.scheme == ContentResolver.SCHEME_FILE) return@runCatching uri.lastPathSegment
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()
