/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.apps.iconpack

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.isActive
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.cygnusx1.qlauncher.apps.AppRepository
import org.cygnusx1.qlauncher.data.ItemOverrideStore
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

/** The downloaded icon set, stored next to its icons. */
@Serializable
data class IconSetManifest(
    /** `owner/repo` on GitHub. */
    val repo: String,
    val tag: String,
    /** The style folders found under [stylesPath]. */
    val styles: List<String>,
    /** The chosen style, or null until one is chosen. */
    val style: String? = null,
    /** The repository's folder that holds the style folders. */
    val stylesPath: String = IconSetNames.DEFAULT_SOURCE.stylesPath,
)

/** One icon of the chosen style. */
data class IconSetIcon(val name: String, val file: File)

/** An app and the icon whose name matches its title. */
data class IconSetMatch(val itemKey: String, val title: String, val icon: IconSetIcon, val hasCustomIcon: Boolean)

/** What the icon set is doing. */
sealed interface IconSetStatus {
    data object Idle : IconSetStatus
    data object LoadingReleases : IconSetStatus

    /** [bytes] of the release archive read so far (GitHub does not send its size). */
    data class Downloading(val tag: String, val bytes: Long) : IconSetStatus
    data class Failed(val reason: Reason, val detail: String? = null) : IconSetStatus

    enum class Reason { BadRepo, NotFound, NoReleases, Network, Http, NoStyles, NoSpace, Storage }
}

/**
 * *Cards › Icon set*: icons from a GitHub repository laid out as `<path>/<style>/<name>.png`
 * ([IconSetSource]), such as DrZacharySmith/projectivy-transparent-icons with `icons/styles`.
 * One set is kept at a time: the archive of the chosen release is streamed and every style
 * folder is extracted (the rest of the archive is skipped), then the user picks the style that
 * *Change icon* offers.
 *
 * Nothing about the downloaded set is a setting (only the list of sources is), so a backup
 * never names icons another TV lacks; an icon
 * put on a card is copied into the backed-up icons folder ([IconPackRepository.saveIconFile]).
 */
class IconSetRepository internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val okHttp: () -> OkHttpClient,
    private val apps: AppRepository,
    private val overrides: ItemOverrideStore,
    private val iconPacks: IconPackRepository,
) {
    private val root = File(context.filesDir, DIR)
    private val staging = File(context.filesDir, "$DIR.tmp")
    private val previous = File(context.filesDir, "$DIR.old")
    private val json = Json { ignoreUnknownKeys = true }

    private val _manifest = MutableStateFlow<IconSetManifest?>(null)

    /** The downloaded set, or null. */
    val manifest: StateFlow<IconSetManifest?> = _manifest.asStateFlow()

    private val _releases = MutableStateFlow<Pair<String, List<IconSetRelease>>?>(null)

    /** The newest releases of the repository last asked about: (`owner/repo`, releases). */
    val releases: StateFlow<Pair<String, List<IconSetRelease>>?> = _releases.asStateFlow()

    private val _status = MutableStateFlow<IconSetStatus>(IconSetStatus.Idle)
    val status: StateFlow<IconSetStatus> = _status.asStateFlow()

    private var job: Job? = null

    /** The request in flight, so [cancel] can stop a read that is waiting on the network. */
    @Volatile private var call: Call? = null

    @Volatile private var iconCache: Pair<String, List<IconSetIcon>>? = null

    init {
        scope.launch(Dispatchers.IO) {
            staging.deleteRecursively()
            previous.deleteRecursively()
            _manifest.value = readManifest()
        }
    }

    /** True while releases load, an archive downloads or the set is being removed. */
    val busy: Boolean get() = job?.isActive == true

    /** Lists the newest releases of [input] (`owner/repo` or a github.com URL). */
    fun loadReleases(input: String) {
        val repo = IconSetNames.parseRepo(input)
        if (repo == null) {
            _releases.value = null
            _status.value = IconSetStatus.Failed(IconSetStatus.Reason.BadRepo)
            return
        }
        if (busy) return
        job = scope.launch(Dispatchers.IO) {
            _status.value = IconSetStatus.LoadingReleases
            val result = try {
                val list = fetchReleases(repo)
                _releases.value = repo to list
                if (list.isEmpty()) IconSetStatus.Failed(IconSetStatus.Reason.NoReleases) else IconSetStatus.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpStatus) {
                _releases.value = repo to emptyList()
                if (e.code == 404) IconSetStatus.Failed(IconSetStatus.Reason.NotFound) else IconSetStatus.Failed(IconSetStatus.Reason.Http, e.code.toString())
            } catch (e: IOException) {
                IconSetStatus.Failed(IconSetStatus.Reason.Network, e.message)
            } catch (e: SerializationException) {
                IconSetStatus.Failed(IconSetStatus.Reason.Http, e.message)
            } catch (e: IllegalArgumentException) {
                IconSetStatus.Failed(IconSetStatus.Reason.Http, e.message)
            }
            // After cancel() the aborted call fails; that is not an error to show.
            if (isActive) _status.value = result
        }
    }

    /**
     * Downloads [release] of [source] and replaces the current set with it. The style chosen
     * before is kept when the new release has it. The same release again does nothing.
     */
    fun download(source: IconSetSource, release: IconSetRelease) {
        if (busy) return
        val repo = source.repo
        val current = _manifest.value
        if (current != null && current.repo.equals(repo, ignoreCase = true) && current.tag == release.tag && current.stylesPath == source.stylesPath) return
        job = scope.launch(Dispatchers.IO) {
            _status.value = IconSetStatus.Downloading(release.tag, 0)
            val result = try {
                val styles = fetchArchive(release.zipUrl, source.stylesPath) { bytes -> if (isActive) _status.value = IconSetStatus.Downloading(release.tag, bytes) }
                if (styles.isEmpty()) {
                    staging.deleteRecursively()
                    IconSetStatus.Failed(IconSetStatus.Reason.NoStyles, source.stylesPath)
                } else {
                    val style = _manifest.value?.style?.takeIf { it in styles }
                    val next = IconSetManifest(repo, release.tag, styles, style, source.stylesPath)
                    writeManifest(File(staging, MANIFEST), next)
                    swapIn()
                    iconCache = null
                    _manifest.value = next
                    IconSetStatus.Idle
                }
            } catch (e: CancellationException) {
                staging.deleteRecursively()
                throw e
            } catch (e: HttpStatus) {
                staging.deleteRecursively()
                IconSetStatus.Failed(IconSetStatus.Reason.Http, e.code.toString())
            } catch (e: NoSpace) {
                staging.deleteRecursively()
                IconSetStatus.Failed(IconSetStatus.Reason.NoSpace)
            } catch (e: StorageFailure) {
                Log.w(TAG, "Cannot store the icon set", e)
                staging.deleteRecursively()
                IconSetStatus.Failed(IconSetStatus.Reason.Storage, e.message)
            } catch (e: IOException) {
                staging.deleteRecursively()
                IconSetStatus.Failed(IconSetStatus.Reason.Network, e.message)
            }
            // After cancel() the aborted call fails; that is not an error to show.
            if (isActive) _status.value = result
        }
    }

    /** Stops a download or release lookup, aborting a read that waits on the network. */
    fun cancel() {
        job?.cancel()
        call?.cancel()
        _status.value = IconSetStatus.Idle
    }

    /** Makes [style] the one *Change icon* and matching use. */
    fun selectStyle(style: String) {
        val m = _manifest.value ?: return
        if (style !in m.styles || style == m.style) return
        val next = m.copy(style = style)
        _manifest.value = next
        iconCache = null
        scope.launch(Dispatchers.IO) {
            runCatching { writeManifest(File(root, MANIFEST), next) }.onFailure { Log.w(TAG, "Cannot save the style", it) }
        }
    }

    /** Deletes the set. Icons already put on cards stay: they are copies. */
    fun remove() {
        job?.cancel()
        call?.cancel()
        job = scope.launch(Dispatchers.IO) {
            _manifest.value = null
            iconCache = null
            root.deleteRecursively()
            staging.deleteRecursively()
            _status.value = IconSetStatus.Idle
        }
    }

    /** The icons of the chosen style by name, or none. */
    suspend fun icons(): List<IconSetIcon> = withContext(Dispatchers.IO) {
        val m = _manifest.value ?: return@withContext emptyList()
        val style = m.style ?: return@withContext emptyList()
        val key = "${m.repo}@${m.tag}/$style"
        iconCache?.takeIf { it.first == key }?.let { return@withContext it.second }
        val files = File(File(root, STYLES), style).listFiles { f -> f.isFile && f.name.endsWith(".png", ignoreCase = true) }.orEmpty().toList()
        val names = IconSetNames.displayNames(files.map { it.name })
        val list = files.map { IconSetIcon(names[it.name] ?: it.nameWithoutExtension, it) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        iconCache = key to list
        list
    }

    /** The chosen style's icons, those whose name fits [title] first. */
    suspend fun iconsFor(title: String): List<IconSetIcon> {
        val all = icons()
        return withContext(Dispatchers.Default) { IconMatcher.rank(title, all) { it.name } }
    }

    /** Every app whose label (or own title) matches an icon name, by title. */
    suspend fun matches(): List<IconSetMatch> {
        val icons = icons()
        if (icons.isEmpty()) return emptyList()
        return withContext(Dispatchers.Default) {
            val keyed = icons.map { it to IconMatcher.key(it.name) }
            val custom = overrides.overrides.value
            apps.apps.value.mapNotNull { app ->
                val icon = IconMatcher.best(listOfNotNull(app.customTitle, app.label), keyed) ?: return@mapNotNull null
                IconSetMatch(app.key, app.title, icon, hasCustomIcon = !custom[app.key]?.iconUri.isNullOrBlank())
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        }
    }

    /** Puts [icon] on the card of [itemKey] (a copy, so it outlives the set). */
    suspend fun apply(itemKey: String, icon: IconSetIcon): Boolean = iconPacks.saveIconFile(itemKey, icon.file)

    /** Gives every card with an icon from a set its own image back; returns how many. */
    suspend fun resetIcons(): Int = withContext(Dispatchers.IO) {
        // The downloaded set's files by size, to recognise older copies of them by their bytes.
        val bySize = File(root, STYLES).listFiles().orEmpty()
            .flatMap { it.listFiles().orEmpty().asList() }
            .filter { it.isFile }
            .groupBy { it.length() }
        iconPacks.resetSetIcons { copy ->
            bySize[copy.length()]?.any { runCatching { it.readBytes().contentEquals(copy.readBytes()) }.getOrDefault(false) } == true
        }
    }

    // --- Storage -----------------------------------------------------------------------------

    private fun readManifest(): IconSetManifest? = runCatching {
        val f = File(root, MANIFEST)
        if (!f.isFile) return null
        json.decodeFromString(IconSetManifest.serializer(), f.readText()).takeIf { m -> m.styles.isNotEmpty() }
    }.onFailure { Log.w(TAG, "Unreadable icon set manifest", it) }.getOrNull()

    private fun writeManifest(file: File, m: IconSetManifest) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(IconSetManifest.serializer(), m))
        if (!tmp.renameTo(file)) throw StorageFailure("Cannot write ${file.name}")
    }

    /** Puts the staged set in place of the current one. */
    private fun swapIn() {
        previous.deleteRecursively()
        if (root.exists() && !root.renameTo(previous)) throw StorageFailure("Cannot replace the icon set")
        if (!staging.renameTo(root)) {
            previous.renameTo(root)
            throw StorageFailure("Cannot move the icon set into place")
        }
        previous.deleteRecursively()
    }

    // --- Network -----------------------------------------------------------------------------

    private fun fetchReleases(repo: String): List<IconSetRelease> {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases?per_page=$RELEASES")
            .header("Accept", "application/vnd.github+json")
            .build()
        return execute(request) { response ->
            if (!response.isSuccessful) throw HttpStatus(response.code)
            IconSetFeed.parseReleases(repo, response.body.string(), RELEASES)
        }
    }

    /** Runs [request] as the call [cancel] can abort. */
    private inline fun <T> execute(request: Request, read: (Response) -> T): T {
        val c = okHttp().newCall(request)
        call = c
        try {
            return c.execute().use(read)
        } finally {
            call = null
        }
    }

    /**
     * Streams the archive at [url] into the staging folder, keeping only icons under
     * `<stylesPath>/<style>/`. Returns the style folders that got icons.
     */
    private suspend fun fetchArchive(url: String, stylesPath: String, progress: (Long) -> Unit): List<String> {
        staging.deleteRecursively()
        val stylesDir = File(staging, STYLES)
        if (!stylesDir.mkdirs()) throw StorageFailure("Cannot create ${stylesDir.name}")
        // An archive of a few hundred megabytes must not go through the HTTP cache.
        val request = Request.Builder().url(url).cacheControl(CacheControl.Builder().noStore().build()).build()
        execute(request) { response ->
            if (!response.isSuccessful) throw HttpStatus(response.code)
            val counted = CountingStream(response.body.byteStream(), progress)
            ZipInputStream(BufferedInputStream(counted, BUFFER)).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val (style, name) = IconSetNames.styleEntry(entry.name, stylesPath) ?: continue
                    val dir = File(stylesDir, style)
                    val out = File(dir, name)
                    if (out.canonicalFile.parentFile != dir.canonicalFile) continue
                    if (stylesDir.usableSpace < MIN_FREE_BYTES) throw NoSpace()
                    val sink = try {
                        dir.mkdirs()
                        WriteGuard(FileOutputStream(out))
                    } catch (e: IOException) {
                        throw StorageFailure(e.message ?: "Cannot create ${out.name}", e)
                    }
                    try {
                        sink.use { zip.copyTo(it, BUFFER) }
                    } catch (e: IOException) {
                        // A write that fails is storage; anything else (a dropped connection, a
                        // truncated or damaged archive) is the download.
                        if (sink.failed) throw StorageFailure(e.message ?: "Cannot write ${out.name}", e)
                        throw e
                    }
                }
            }
        }
        return stylesDir.listFiles { f -> f.isDirectory && f.list().orEmpty().isNotEmpty() }.orEmpty().map { it.name }.sorted()
    }

    /** Counts the bytes read, reporting each further megabyte. */
    private class CountingStream(input: InputStream, private val progress: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L
        private var reported = 0L

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) add(it.toLong()) }

        private fun add(n: Long) {
            count += n
            if (count - reported >= REPORT_STEP) {
                reported = count
                progress(count)
            }
        }
    }

    /** Remembers whether writing to [out] failed, to tell a full disk from a bad download. */
    private class WriteGuard(out: OutputStream) : FilterOutputStream(out) {
        var failed = false
            private set

        override fun write(b: Int) = guard { out.write(b) }

        override fun write(b: ByteArray, off: Int, len: Int) = guard { out.write(b, off, len) }

        override fun flush() = guard { out.flush() }

        override fun close() = guard { out.close() }

        private inline fun guard(block: () -> Unit) = try {
            block()
        } catch (e: IOException) {
            failed = true
            throw e
        }
    }

    private class HttpStatus(val code: Int) : IOException("HTTP $code")

    private open class StorageFailure(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** Fewer than [MIN_FREE_BYTES] left: the download stops before it fills the TV. */
    private class NoSpace : StorageFailure("Not enough free space")

    companion object {
        private const val TAG = "QLIconSet"
        private const val DIR = "icon_set"
        private const val STYLES = "styles"
        private const val MANIFEST = "set.json"

        /** How many of a repository's newest releases are offered. */
        const val RELEASES = 3
        private const val BUFFER = 64 * 1024
        private const val REPORT_STEP = 1L shl 20
        private const val MIN_FREE_BYTES = 100L shl 20
    }
}
