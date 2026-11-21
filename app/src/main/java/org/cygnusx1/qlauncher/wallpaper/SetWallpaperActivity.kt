/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.container
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * *Set as QLauncher wallpaper*: accepts `VIEW` and `SEND` of a picture or video (data URI or
 * `EXTRA_STREAM`), copies it to `files/background`, stores it as the *Media file* wallpaper (a
 * video when the MIME type starts with `video/`) and switches the source to *Media file*.
 */
class SetWallpaperActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val uri = sharedUri(intent)
        if (uri == null) {
            Toast.makeText(this, R.string.wallpaper_set_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val module = container.wallpaper
        lifecycleScope.launch {
            val ok = module.setFromExternal(uri, intent.type)
            Toast.makeText(applicationContext, if (ok) R.string.wallpaper_set_done else R.string.wallpaper_set_failed, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun sharedUri(intent: Intent): Uri? {
        if (intent.action == Intent.ACTION_SEND) {
            val stream = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (stream != null) return stream
        }
        return intent.data
    }
}

/** The `files/background` directory that holds the copied *Media file* wallpaper. */
internal object BackgroundFiles {
    private const val DIR = "background"
    private const val MAX_BYTES = 512L * 1024 * 1024

    fun dir(context: Context): File = File(context.filesDir, DIR)

    /** Copies [uri] into the directory; the name keeps a usable extension. */
    fun copy(context: Context, uri: Uri, mimeType: String? = null): File {
        val input = when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> File(uri.path.orEmpty()).inputStream()
            else -> context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open")
        }
        val mime = mimeType ?: context.contentResolver.getType(uri)
        val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?: uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.length in 2..5 }
            ?: "bin"
        return copy(context, input, ext)
    }

    fun copy(context: Context, input: InputStream, extension: String): File {
        val dir = dir(context).apply { mkdirs() }
        val target = File(dir, "wallpaper-${System.currentTimeMillis()}.$extension")
        val tmp = File(dir, target.name + ".part")
        input.use { src ->
            tmp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = src.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) {
                        tmp.delete()
                        throw IOException("too large")
                    }
                    out.write(buffer, 0, n)
                }
            }
        }
        if (!tmp.renameTo(target)) throw IOException("cannot store")
        return target
    }

    /** Deletes every copied wallpaper except [keepPath]. */
    fun pruneExcept(context: Context, keepPath: String?) {
        val keep = keepPath?.let { File(it).canonicalPath }
        dir(context).listFiles()?.forEach { f ->
            if (f.canonicalPath != keep) f.delete()
        }
    }
}
