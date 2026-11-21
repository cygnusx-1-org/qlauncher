/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.source

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.settings.WallpaperProviders
import org.cygnusx1.qlauncher.shared.NotificationSource
import org.cygnusx1.qlauncher.shared.Program
import org.cygnusx1.qlauncher.shared.ProgramSource
import org.cygnusx1.qlauncher.ui.theme.DynamicPalette
import org.cygnusx1.qlauncher.wallpaper.UpdateMode
import org.cygnusx1.qlauncher.wallpaper.Wallpaper
import org.cygnusx1.qlauncher.wallpaper.WallpaperEvent
import org.cygnusx1.qlauncher.wallpaper.WallpaperType
import java.io.File
import java.util.Locale

private const val HOUR = 60 * 60_000L

/** *None*: a solid black background. */
class NoneProvider : WallpaperProvider {
    override val id = WallpaperProviders.NONE
    override val updateMode = UpdateMode.TIME_ELAPSED
    override val cacheMillis = HOUR
    override suspend fun getWallpapers(event: WallpaperEvent, params: String) = listOf(Wallpaper.NONE)
}

/** *Default wallpaper*: the bundled picture. */
class DefaultProvider : WallpaperProvider {
    override val id = WallpaperProviders.DEFAULT
    override val updateMode = UpdateMode.TIME_ELAPSED
    override val cacheMillis = HOUR
    override suspend fun getWallpapers(event: WallpaperEvent, params: String) = listOf(Wallpaper.DEFAULT)
}

/** The two colours the *Dynamic colors* gradient animates to. */
data class DynamicGradient(val light: Int, val dark: Int)

/**
 * *Dynamic colors*: no picture. On a CardFocused event it takes the card's light and dark colours
 * (each falls back to the other), seeds the palette with the light one and sets the gradient to
 * tone 600 of the light seed and tone 800 of the dark seed; the host animates to it over 1000 ms,
 * linearly. Until a card is focused the gradient follows the current palette.
 */
class DynamicColorsProvider(private val offerSeed: (Int) -> Unit) : WallpaperProvider {
    override val id = WallpaperProviders.DYNAMIC_COLORS
    override val updateMode = UpdateMode.CARD_FOCUSED
    override val cacheMillis = 0L

    private val _gradient = MutableStateFlow<DynamicGradient?>(null)

    /** The colours from the last focused card; null until one is focused. */
    val gradient: StateFlow<DynamicGradient?> = _gradient.asStateFlow()

    override suspend fun getWallpapers(event: WallpaperEvent, params: String): List<Wallpaper> {
        if (event is WallpaperEvent.CardFocused) {
            val light = event.lightColor.takeIf { it != 0 } ?: event.darkColor
            val dark = event.darkColor.takeIf { it != 0 } ?: event.lightColor
            if (light != 0) {
                offerSeed(light)
                val l = DynamicPalette.fromSeed(light).shade(600) ?: light
                val d = DynamicPalette.fromSeed(dark).shade(800) ?: dark
                _gradient.value = DynamicGradient(l, d)
            }
        }
        return listOf(Wallpaper.DYNAMIC_COLORS)
    }
}

private val EXTENSION_MIME = mapOf(
    "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp",
    "gif" to "image/gif", "bmp" to "image/bmp", "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif",
    "mp4" to "video/mp4", "m4v" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm",
    "mov" to "video/quicktime", "3gp" to "video/3gpp", "ts" to "video/mp2t",
    "json" to "application/json",
)

/** Guesses a wallpaper type from a MIME type or a file name; null when it is not a wallpaper. */
internal fun wallpaperTypeOf(mime: String?, name: String?): WallpaperType? {
    val m = mime?.lowercase(Locale.ROOT) ?: name?.let { n ->
        val ext = n.substringAfterLast('.', "").lowercase(Locale.ROOT)
        EXTENSION_MIME[ext] ?: runCatching { MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) }.getOrNull()
    }
    return when {
        m == null -> null
        m == "application/json" -> WallpaperType.LOTTIE
        m.startsWith("video/") -> WallpaperType.VIDEO
        m == "image/gif" -> WallpaperType.ANIMATED
        m.startsWith("image/") -> WallpaperType.IMAGE
        else -> null
    }
}

/**
 * *Media file*: one stored wallpaper (a serialised [Wallpaper]), a `#RRGGBB` colour, or a bare URI
 * from an older value.
 */
class MediaFileProvider : WallpaperProvider {
    override val id = WallpaperProviders.MEDIA_FILE
    override val updateMode = UpdateMode.TIME_ELAPSED
    override val cacheMillis = 0L

    override suspend fun getWallpapers(event: WallpaperEvent, params: String): List<Wallpaper> =
        listOf(parse(params) ?: throw ProviderException(R.string.wallpaper_error_no_media))

    companion object {
        fun parse(params: String): Wallpaper? {
            val p = params.trim()
            if (p.isEmpty()) return null
            if (Wallpaper.isColorValue(p)) return Wallpaper(uri = p.uppercase(Locale.ROOT), type = WallpaperType.COLOR)
            Wallpaper.fromJson(p)?.let { return it }
            if (p.startsWith("{")) return null
            val type = wallpaperTypeOf(null, p.substringBefore('?').substringAfterLast('/')) ?: WallpaperType.IMAGE
            return Wallpaper(uri = p, type = type)
        }
    }
}

/**
 * *Folder*: every picture, video and Lottie file below a folder, recursively. The parameter is a
 * SAF tree URI (with a persisted read grant) or a `file:` URI.
 */
class FolderProvider(private val resolver: ContentResolver) : WallpaperProvider {
    override val id = WallpaperProviders.FOLDER
    override val updateMode = UpdateMode.TIME_ELAPSED
    override val cacheMillis = HOUR

    override suspend fun getWallpapers(event: WallpaperEvent, params: String): List<Wallpaper> = withContext(Dispatchers.IO) {
        val p = params.trim()
        if (p.isEmpty()) throw ProviderException(R.string.wallpaper_error_no_folder)
        val uri = Uri.parse(p)
        val items = try {
            when (uri.scheme) {
                ContentResolver.SCHEME_CONTENT -> walkTree(uri)
                ContentResolver.SCHEME_FILE, null -> walkFile(File(uri.path ?: p))
                else -> emptyList()
            }
        } catch (e: SecurityException) {
            throw ProviderException(R.string.wallpaper_error_folder_access, e)
        }
        items.ifEmpty { throw ProviderException(R.string.wallpaper_error_folder_empty) }
    }

    private fun walkFile(root: File): List<Wallpaper> {
        if (!root.isDirectory) throw ProviderException(R.string.wallpaper_error_folder_access)
        return root.walkTopDown().maxDepth(MAX_DEPTH)
            .filter { it.isFile && !it.name.startsWith(".") }
            .mapNotNull { f -> wallpaperTypeOf(null, f.name)?.let { Wallpaper(uri = Uri.fromFile(f).toString(), type = it, title = f.nameWithoutExtension) } }
            .take(MAX_ITEMS)
            .sortedBy { it.uri }
            .toList()
    }

    private fun walkTree(tree: Uri): List<Wallpaper> {
        val out = mutableListOf<Wallpaper>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        fun walk(docId: String, depth: Int) {
            if (depth > MAX_DEPTH || out.size >= MAX_ITEMS) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext() && out.size < MAX_ITEMS) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1)
                    val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(id, depth + 1)
                    } else if (name == null || !name.startsWith(".")) {
                        val type = wallpaperTypeOf(mime, name) ?: continue
                        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        out += Wallpaper(uri = doc.toString(), type = type, title = name?.substringBeforeLast('.'))
                    }
                }
            }
        }
        walk(rootId, 0)
        return out.sortedBy { it.title.orEmpty().lowercase(Locale.ROOT) }
    }

    companion object {
        private const val MAX_DEPTH = 8
        private const val MAX_ITEMS = 2000
    }
}

/**
 * *Theme*: the files of the imported theme's wallpaper folder, which the backup subsystem fills:
 * `.json` → Lottie, `.mp4`/`.mkv`/`.webm` → video, anything else → picture.
 */
class ThemeProvider(private val folder: File) : WallpaperProvider {
    override val id = WallpaperProviders.THEME
    override val updateMode = UpdateMode.TIME_ELAPSED
    override val cacheMillis = HOUR

    override suspend fun getWallpapers(event: WallpaperEvent, params: String): List<Wallpaper> = withContext(Dispatchers.IO) {
        val files = folder.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) throw ProviderException(R.string.wallpaper_error_theme_empty)
        files.map { f ->
            val type = when (f.extension.lowercase(Locale.ROOT)) {
                "json" -> WallpaperType.LOTTIE
                "mp4", "mkv", "webm" -> WallpaperType.VIDEO
                else -> WallpaperType.IMAGE
            }
            Wallpaper(uri = Uri.fromFile(f).toString(), type = type, title = f.nameWithoutExtension)
        }
    }
}

/** *Focused program*: the art of the focused program card. */
class FocusedProgramProvider : WallpaperProvider {
    override val id = WallpaperProviders.FOCUSED_PROGRAM
    override val updateMode = UpdateMode.PROGRAM_CARD_FOCUSED
    override val cacheMillis = 0L

    @Volatile
    private var last: Wallpaper? = null

    override suspend fun getWallpapers(event: WallpaperEvent, params: String): List<Wallpaper> {
        if (event is WallpaperEvent.ProgramCardFocused) {
            val uri = event.iconUri
            if (!uri.isNullOrBlank()) last = Wallpaper(uri = uri, type = WallpaperType.IMAGE, title = event.title)
        }
        return listOfNotNull(last)
    }
}

/**
 * *Random program*: preview programs whose poster art is unset, 16:9, 3:2 or 4:3 and whose
 * duration is unknown or longer than 15 minutes, shuffled. The program's intent becomes the
 * wallpaper's action.
 */
class RandomProgramProvider(private val source: () -> ProgramSource?) : WallpaperProvider {
    override val id = WallpaperProviders.RANDOM_PROGRAM
    override val updateMode = UpdateMode.TIME_ELAPSED
    override val cacheMillis = 2 * HOUR

    override suspend fun getWallpapers(event: WallpaperEvent, params: String): List<Wallpaper> {
        val s = source() ?: throw ProviderException(R.string.wallpaper_error_no_channels)
        if (!s.available.value) throw ProviderException(R.string.wallpaper_error_no_channels)
        return select(s.allPreviewPrograms()).shuffled()
    }

    companion object {
        private const val MIN_DURATION = 15 * 60_000L

        /** The programs that make a wallpaper, in their original order. */
        fun select(programs: List<Program>): List<Wallpaper> = programs
            .filter { it.posterArtAspectRatio in -1..2 && (it.durationMillis <= 0L || it.durationMillis > MIN_DURATION) }
            .mapNotNull { p ->
                val art = p.posterArtUri?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Wallpaper(uri = art, type = WallpaperType.IMAGE, title = p.title, actionUri = p.intentUri)
            }
            .distinctBy { it.uri }
    }
}

/** *Now Playing*: the artwork of the active media session; title, artist and album come along. */
class NowPlayingProvider(private val source: () -> NotificationSource?) : WallpaperProvider {
    override val id = WallpaperProviders.NOW_PLAYING
    override val updateMode = UpdateMode.NOW_PLAYING_CHANGED
    override val cacheMillis = 0L

    override suspend fun getWallpapers(event: WallpaperEvent, params: String): List<Wallpaper> {
        val s = source()
        if (event is WallpaperEvent.NowPlayingChanged) {
            return listOfNotNull(wallpaperOf(event.iconUri, event.title, event.artist, event.album))
        }
        if (s == null || !s.accessGranted.value) throw ProviderException(R.string.wallpaper_error_no_notification_access)
        val np = s.nowPlaying.value ?: return emptyList()
        return listOfNotNull(wallpaperOf(np.artUri, np.title, np.artist, np.album))
    }

    private fun wallpaperOf(art: String?, title: String?, artist: String?, album: String?): Wallpaper? {
        if (art.isNullOrBlank()) return null
        val by = listOfNotNull(artist?.takeIf { it.isNotBlank() }, album?.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { null }
        return Wallpaper(uri = art, type = WallpaperType.IMAGE, title = title, author = by)
    }
}
