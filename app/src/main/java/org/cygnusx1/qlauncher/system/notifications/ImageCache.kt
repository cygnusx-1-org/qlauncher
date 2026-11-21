/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.system.notifications

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Writes notification icons and album art as PNG files in one cache sub-directory and hands out
 * `file:` URIs (Coil loads them directly). Files are named by a hash of a caller-chosen key, so
 * the same image is written once; [retainOnly] removes the ones no longer in use.
 *
 * Call from a background thread.
 */
class ImageCache(cacheDir: File, name: String, private val maxSidePx: Int) {
    private val dir = File(cacheDir, name)

    /** Writes [drawable] under [key] unless it is already there; returns its URI, or null on failure. */
    fun put(key: String, drawable: Drawable): String? {
        val file = fileFor(key)
        if (file.isFile) return Uri.fromFile(file).toString()
        val bitmap = toBitmap(drawable) ?: return null
        return write(file, bitmap)
    }

    fun put(key: String, bitmap: Bitmap): String? {
        val file = fileFor(key)
        if (file.isFile) return Uri.fromFile(file).toString()
        val b = try {
            scaled(bitmap)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot scale image", e)
            return null
        }
        return write(file, b)
    }

    /** Deletes every file whose key is not in [keys]. */
    fun retainOnly(keys: Collection<String>) {
        val keep = keys.mapTo(HashSet()) { fileFor(it).name }
        dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    fun fileFor(key: String): File = File(dir, hash(key) + ".png")

    private fun write(file: File, bitmap: Bitmap): String? = try {
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        val ok = tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!ok || !tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("PNG not written")
        }
        Uri.fromFile(file).toString()
    } catch (e: IOException) {
        Log.w(TAG, "Cannot write ${file.name}", e)
        null
    } catch (e: RuntimeException) {
        // For example a bitmap that cannot be read back.
        Log.w(TAG, "Cannot encode ${file.name}", e)
        null
    }

    private fun toBitmap(d: Drawable): Bitmap? {
        if (d is BitmapDrawable && d.bitmap != null) return scaled(d.bitmap)
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: maxSidePx
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: maxSidePx
        val scale = minOf(1f, maxSidePx.toFloat() / maxOf(w, h))
        val bw = (w * scale).toInt().coerceAtLeast(1)
        val bh = (h * scale).toInt().coerceAtLeast(1)
        return try {
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            d.setBounds(0, 0, bw, bh)
            d.draw(canvas)
            bmp
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot draw icon", e)
            null
        }
    }

    private fun scaled(b: Bitmap): Bitmap {
        val side = maxOf(b.width, b.height)
        if (side <= maxSidePx) return b
        val s = maxSidePx.toFloat() / side
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    private fun hash(key: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
        return d.take(12).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "QLImageCache"
    }
}
