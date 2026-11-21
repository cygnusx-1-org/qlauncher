/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.engine

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import coil3.gif.MovieDrawable
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.wallpaper.Wallpaper
import org.cygnusx1.qlauncher.wallpaper.WallpaperType
import org.cygnusx1.qlauncher.wallpaper.source.await
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.min

/**
 * Downloads remote pictures and Lottie files into the cache directory, keyed by URL, so a list that
 * rotates does not download the same picture twice. Trimmed to [maxBytes], oldest first.
 */
class DownloadCache(private val dir: File, private val client: OkHttpClient, private val maxBytes: Long = 150L * 1024 * 1024) {

    /** The local copy of [url], downloading it when needed. */
    suspend fun fetch(url: String): File {
        val file = File(dir, key(url))
        if (file.isFile && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        val request = Request.Builder().url(url).build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val length = response.body.contentLength()
            if (length > MAX_FILE_BYTES) throw IOException("too large")
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                val tmp = File(dir, file.name + ".part")
                response.body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > MAX_FILE_BYTES) throw IOException("too large")
                            out.write(buffer, 0, n)
                        }
                    }
                }
                if (!tmp.renameTo(file)) throw IOException("cannot store download")
                trim()
            }
        }
        return file
    }

    /** The cached copy of [url] if there is one. */
    fun cached(url: String): File? = File(dir, key(url)).takeIf { it.isFile && it.length() > 0 }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun trim() {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (f in files) {
            total += f.length()
            if (total > maxBytes) f.delete()
        }
    }

    private fun key(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_FILE_BYTES = 64L * 1024 * 1024
    }
}

/**
 * Decodes wallpapers on the device: pictures with `ImageDecoder` (original size read from the
 * header, decoded just large enough to cover the screen, in software so they can be blurred and
 * sampled), animated pictures as `AnimatedImageDrawable`, Lottie files with the Lottie parser,
 * colours, and the bundled default. Videos are only checked and are decoded by the player.
 *
 * Before Android 9 there is no `ImageDecoder`: pictures go through `BitmapFactory` with the EXIF
 * orientation applied by hand, animated GIFs play through `Movie`, and animated WebP and APNG
 * show their first frame.
 */
class AndroidWallpaperLoader(
    private val context: Context,
    private val downloads: DownloadCache,
    private val screenSize: () -> Pair<Int, Int>,
) : WallpaperLoader {
    private val resolver: ContentResolver get() = context.contentResolver

    override suspend fun load(wallpaper: Wallpaper): Result<WallpaperContent> = try {
        Result.success(withContext(Dispatchers.IO) { loadInternal(wallpaper) })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    private suspend fun loadInternal(w: Wallpaper): WallpaperContent {
        if (w.uri == Wallpaper.DYNAMIC_COLORS.uri) return WallpaperContent.DynamicColors
        if (w.uri == Wallpaper.DEFAULT.uri) return renderDefault()
        return when (w.type) {
            WallpaperType.COLOR -> WallpaperContent.Solid(Wallpaper.parseColor(w.uri) ?: throw IOException("not a colour"))
            WallpaperType.VIDEO -> checkVideo(w)
            WallpaperType.LOTTIE -> loadLottie(w)
            WallpaperType.IMAGE, WallpaperType.ANIMATED -> decodeImage(w)
        }
    }

    private fun renderDefault(): WallpaperContent {
        val (sw, sh) = screenSize()
        val drawable = ContextCompat.getDrawable(context, R.drawable.wallpaper_default) ?: throw IOException("no default")
        val bitmap = drawable.toBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        return WallpaperContent.Still(bitmap, sw, sh, SeedColors.extract(bitmap))
    }

    private fun checkVideo(w: Wallpaper): WallpaperContent {
        val uri = Uri.parse(w.uri)
        when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> if (!File(uri.path.orEmpty()).isFile) throw FileNotFoundException(w.uri)
            ContentResolver.SCHEME_CONTENT -> resolver.openAssetFileDescriptor(uri, "r")?.close() ?: throw FileNotFoundException(w.uri)
        }
        return WallpaperContent.Video(w.uri, w.width, w.height)
    }

    private suspend fun open(uriString: String): InputStream {
        val uri = Uri.parse(uriString)
        return when (uri.scheme) {
            "http", "https" -> downloads.fetch(uriString).inputStream()
            ContentResolver.SCHEME_FILE -> File(uri.path.orEmpty()).inputStream()
            else -> resolver.openInputStream(uri) ?: throw FileNotFoundException(uriString)
        }
    }

    @RequiresApi(28)
    private suspend fun source(uriString: String): ImageDecoder.Source {
        val uri = Uri.parse(uriString)
        return when (uri.scheme) {
            "http", "https" -> ImageDecoder.createSource(downloads.fetch(uriString))
            ContentResolver.SCHEME_FILE -> ImageDecoder.createSource(File(uri.path.orEmpty()))
            else -> ImageDecoder.createSource(resolver, uri)
        }
    }

    private suspend fun loadLottie(w: Wallpaper): WallpaperContent {
        val composition: LottieComposition = open(w.uri).use { input ->
            val result = LottieCompositionFactory.fromJsonInputStreamSync(input, w.uri)
            result.value ?: throw IOException("not a Lottie file", result.exception)
        }
        val bounds = composition.bounds
        val frame = runCatching { lottieFrame(composition) }.getOrNull()
        return WallpaperContent.Lottie(composition, frame, bounds.width(), bounds.height(), frame?.let(SeedColors::extract))
    }

    private fun lottieFrame(composition: LottieComposition): Bitmap {
        val b = composition.bounds
        val (w, h) = DecodeSize.fitInside(b.width(), b.height(), FRAME_WIDTH, FRAME_HEIGHT)
        val bitmap = createBitmap(w, h)
        val drawable = LottieDrawable()
        drawable.setComposition(composition)
        drawable.progress = 0f
        drawable.setBounds(0, 0, w, h)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private suspend fun decodeImage(w: Wallpaper): WallpaperContent =
        if (Build.VERSION.SDK_INT >= 28) decodeWithImageDecoder(w) else decodeWithBitmapFactory(w)

    @RequiresApi(28)
    private suspend fun decodeWithImageDecoder(w: Wallpaper): WallpaperContent {
        val (sw, sh) = screenSize()
        val src = source(w.uri)
        var originalWidth = 0
        var originalHeight = 0
        val drawable: Drawable = ImageDecoder.decodeDrawable(src) { decoder, info, _ ->
            originalWidth = info.size.width
            originalHeight = info.size.height
            val (tw, th) = DecodeSize.target(originalWidth, originalHeight, sw, sh)
            if (tw != originalWidth || th != originalHeight) decoder.setTargetSize(tw, th)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        if (drawable is AnimatedImageDrawable) {
            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            val frame = firstFrame(source(w.uri), originalWidth, originalHeight)
            return WallpaperContent.Animated(drawable, frame, originalWidth, originalHeight, SeedColors.extract(frame))
        }
        val bitmap = (drawable as? BitmapDrawable)?.bitmap
            ?.let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
            ?: drawable.toBitmap(drawable.intrinsicWidth.coerceAtLeast(1), drawable.intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        return WallpaperContent.Still(bitmap, originalWidth, originalHeight, SeedColors.extract(bitmap))
    }

    /** The first frame of an animated picture, small: it only feeds blurred backgrounds and the seed. */
    @RequiresApi(28)
    private fun firstFrame(src: ImageDecoder.Source, width: Int, height: Int): Bitmap =
        ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
            val (fw, fh) = DecodeSize.fitInside(width, height, FRAME_WIDTH, FRAME_HEIGHT)
            decoder.setTargetSize(min(fw, width.coerceAtLeast(1)), min(fh, height.coerceAtLeast(1)))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    /**
     * [decodeWithImageDecoder] for Android 7 and 8: the same sizes from `BitmapFactory`, sampled
     * down by a power of two and then scaled to the exact target, with the EXIF orientation applied.
     */
    private suspend fun decodeWithBitmapFactory(w: Wallpaper): WallpaperContent {
        val (sw, sh) = screenSize()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(w.uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("not a picture")
        val gif = bounds.outMimeType == "image/gif"
        val exif = if (gif) Orientation.NONE else readOrientation(w.uri)
        val originalWidth = if (exif.swapsSides) bounds.outHeight else bounds.outWidth
        val originalHeight = if (exif.swapsSides) bounds.outWidth else bounds.outHeight
        if (gif) {
            readMovie(w.uri)?.let { movie ->
                val drawable = MovieDrawable(movie).apply { setRepeatCount(MovieDrawable.REPEAT_INFINITE) }
                val (fw, fh) = DecodeSize.fitInside(originalWidth, originalHeight, FRAME_WIDTH, FRAME_HEIGHT)
                val frame = decodeSampled(w.uri, bounds.outWidth, bounds.outHeight, fw, fh, exif)
                return WallpaperContent.Animated(drawable, frame, originalWidth, originalHeight, SeedColors.extract(frame))
            }
        }
        val (tw, th) = DecodeSize.target(originalWidth, originalHeight, sw, sh)
        val bitmap = decodeSampled(w.uri, bounds.outWidth, bounds.outHeight, tw, th, exif)
        return WallpaperContent.Still(bitmap, originalWidth, originalHeight, SeedColors.extract(bitmap))
    }

    /**
     * Decodes [uriString] (stored [rawWidth] × [rawHeight]) to [width] × [height] after
     * [orientation] is applied: the largest power-of-two sample that stays at or above the target,
     * then an exact scale, then the rotation or mirror.
     */
    private suspend fun decodeSampled(
        uriString: String,
        rawWidth: Int,
        rawHeight: Int,
        width: Int,
        height: Int,
        orientation: Orientation,
    ): Bitmap {
        val targetW = if (orientation.swapsSides) height else width
        val targetH = if (orientation.swapsSides) width else height
        var sample = 1
        while (rawWidth / (sample * 2) >= targetW && rawHeight / (sample * 2) >= targetH) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = open(uriString).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("cannot decode $uriString")
        val scaled = if (decoded.width == targetW && decoded.height == targetH) {
            decoded
        } else {
            decoded.scale(targetW, targetH).also { if (it !== decoded) decoded.recycle() }
        }
        if (orientation == Orientation.NONE) return scaled
        val matrix = Matrix().apply {
            if (orientation.flipped) postScale(-1f, 1f)
            if (orientation.degrees != 0) postRotate(orientation.degrees.toFloat())
        }
        return Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true)
            .also { if (it !== scaled) scaled.recycle() }
    }

    /** The picture's EXIF orientation; none when it has no EXIF data or it cannot be read. */
    private suspend fun readOrientation(uriString: String): Orientation = try {
        open(uriString).use { input ->
            val exif = ExifInterface(input)
            Orientation(exif.rotationDegrees, exif.isFlipped)
        }
    } catch (e: IOException) {
        Orientation.NONE
    } catch (e: RuntimeException) {
        Orientation.NONE
    }

    /** An animated GIF as a `Movie`; null for a single frame, a damaged file or one over [MAX_GIF_BYTES]. */
    @Suppress("DEPRECATION") // Movie: the only GIF player before Android 9.
    private suspend fun readMovie(uriString: String): android.graphics.Movie? {
        val bytes = open(uriString).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_GIF_BYTES) return null
            }
            out.toByteArray()
        }
        val movie = android.graphics.Movie.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return movie.takeIf { it.duration() > 0 && it.width() > 0 && it.height() > 0 }
    }

    /** An EXIF orientation as a clockwise rotation, after a horizontal mirror when [flipped]. */
    private data class Orientation(val degrees: Int, val flipped: Boolean) {
        val swapsSides: Boolean get() = degrees == 90 || degrees == 270

        companion object {
            val NONE = Orientation(0, false)
        }
    }

    companion object {
        /** Size bound of the still frames taken from animations. */
        private const val FRAME_WIDTH = 480
        private const val FRAME_HEIGHT = 270

        /** Larger GIFs are shown as their first frame before Android 9: `Movie` holds the whole file. */
        private const val MAX_GIF_BYTES = 32 * 1024 * 1024
    }
}
