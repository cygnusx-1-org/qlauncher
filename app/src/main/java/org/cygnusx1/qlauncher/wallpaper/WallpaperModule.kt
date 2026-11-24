/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cygnusx1.qlauncher.AppContainer
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.SettingsRepository
import org.cygnusx1.qlauncher.settings.WallpaperProviders
import org.cygnusx1.qlauncher.shared.NotificationSource
import org.cygnusx1.qlauncher.shared.ProgramSource
import org.cygnusx1.qlauncher.wallpaper.engine.Acceptance
import org.cygnusx1.qlauncher.wallpaper.engine.AndroidWallpaperLoader
import org.cygnusx1.qlauncher.wallpaper.engine.DownloadCache
import org.cygnusx1.qlauncher.wallpaper.engine.EngineConfig
import org.cygnusx1.qlauncher.wallpaper.engine.LastWallpaperStore
import org.cygnusx1.qlauncher.wallpaper.engine.WallpaperController
import org.cygnusx1.qlauncher.wallpaper.plugin.PluginRegistry
import org.cygnusx1.qlauncher.wallpaper.plugin.PluginWallpaperProvider
import org.cygnusx1.qlauncher.wallpaper.reddit.KeystoreTokenCipher
import org.cygnusx1.qlauncher.wallpaper.reddit.RedditApi
import org.cygnusx1.qlauncher.wallpaper.reddit.RedditFilter
import org.cygnusx1.qlauncher.wallpaper.reddit.RedditListCache
import org.cygnusx1.qlauncher.wallpaper.reddit.RedditLoginActivity
import org.cygnusx1.qlauncher.wallpaper.reddit.RedditPreset
import org.cygnusx1.qlauncher.wallpaper.reddit.RedditPresets
import org.cygnusx1.qlauncher.wallpaper.reddit.RedditProvider
import org.cygnusx1.qlauncher.wallpaper.reddit.RedditTokenStore
import org.cygnusx1.qlauncher.wallpaper.reddit.RedditTokens
import org.cygnusx1.qlauncher.wallpaper.source.DefaultProvider
import org.cygnusx1.qlauncher.wallpaper.source.DynamicColorsProvider
import org.cygnusx1.qlauncher.wallpaper.source.FocusedProgramProvider
import org.cygnusx1.qlauncher.wallpaper.source.FolderProvider
import org.cygnusx1.qlauncher.wallpaper.source.MediaFileProvider
import org.cygnusx1.qlauncher.wallpaper.source.NoneProvider
import org.cygnusx1.qlauncher.wallpaper.source.NowPlayingProvider
import org.cygnusx1.qlauncher.wallpaper.source.ProviderException
import org.cygnusx1.qlauncher.wallpaper.source.ProviderInfo
import org.cygnusx1.qlauncher.wallpaper.source.ProviderParamsStore
import org.cygnusx1.qlauncher.wallpaper.source.ProviderStatus
import org.cygnusx1.qlauncher.wallpaper.source.RandomProgramProvider
import org.cygnusx1.qlauncher.wallpaper.source.ThemeProvider
import org.cygnusx1.qlauncher.wallpaper.source.WallpaperProvider
import org.cygnusx1.qlauncher.wallpaper.source.WikimediaCategory
import org.cygnusx1.qlauncher.wallpaper.source.WikimediaProvider
import org.cygnusx1.qlauncher.wallpaper.ui.VideoPositionMemory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The wallpaper subsystem: providers, the update loop ([controller]), Reddit login state and the
 * per-provider parameters. One instance per process, from `AppContainer.wallpaper`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WallpaperModule(private val container: AppContainer) {
    private val app = container.app
    private val scope = container.scope
    private val settings: SettingsRepository = container.settings

    /** Single-threaded: the controller's state lives here. */
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    /** Per-provider parameters, in `datastore/wallpaper_params.preferences_pb`. */
    val params = ProviderParamsStore(
        PreferenceDataStoreFactory.create(scope = scope) { app.preferencesDataStoreFile("wallpaper_params") },
        scope,
    )

    // ---- Late-bound dependencies implemented by other subsystems ----

    private val programSourceFlow = MutableStateFlow<ProgramSource?>(null)
    private val notificationSourceFlow = MutableStateFlow<NotificationSource?>(null)

    /** Set by the channels subsystem; null means "no programs". */
    var programSource: ProgramSource?
        get() = programSourceFlow.value
        set(value) {
            programSourceFlow.value = value
            controller.invalidate(WallpaperProviders.RANDOM_PROGRAM)
        }

    /** Set by the notification listener; null means "no Now Playing". */
    var notificationSource: NotificationSource?
        get() = notificationSourceFlow.value
        set(value) {
            notificationSourceFlow.value = value
            controller.invalidate(WallpaperProviders.NOW_PLAYING)
        }

    // ---- Reddit ----

    private val redditTokens = RedditTokenStore(
        PreferenceDataStoreFactory.create(scope = scope) { app.preferencesDataStoreFile(RedditTokenStore.FILE_NAME) },
        KeystoreTokenCipher(),
        scope,
    )

    internal val redditApi = RedditApi(
        client = container.okHttp.newBuilder().cache(null).build(),
        credentials = redditTokens,
        onLoggedOut = { Log.i(TAG, "Reddit access was revoked; logged out") },
    )

    private val redditCache = RedditListCache(File(app.filesDir, "wallpaper/reddit_last.json"))

    /** The Reddit user name (`u/<name>` row) or null while logged out; empty while the name is not known yet. */
    val redditUser: StateFlow<String?> = redditTokens.username

    // ---- Providers ----

    /** The *Dynamic colors* provider; [ui.WallpaperHost][org.cygnusx1.qlauncher.wallpaper.ui.WallpaperHost] draws its gradient. */
    val dynamicColors = DynamicColorsProvider { container.theme.offerSeed(it) }

    /** Where the backup subsystem unpacks the current theme's wallpapers. */
    val themeWallpaperFolder: File = File(app.filesDir, "themes/current/wallpapers")

    private val builtIns: Map<String, WallpaperProvider> = listOf(
        NoneProvider(),
        DefaultProvider(),
        dynamicColors,
        FolderProvider(app.contentResolver),
        MediaFileProvider(),
        ThemeProvider(themeWallpaperFolder),
        FocusedProgramProvider(),
        RandomProgramProvider { programSourceFlow.value },
        NowPlayingProvider { notificationSourceFlow.value },
        WikimediaProvider(container.okHttp, File(app.cacheDir, "wallpaper")),
        RedditProvider(redditApi, redditTokens.username, redditCache) {
            val s = settings.current
            RedditFilter(
                minAcceptedWidth = s[Keys.Wallpaper.minAcceptedWidth],
                skipWrongFormat = s[Keys.Wallpaper.wrongFormatMode] == Acceptance.WRONG_FORMAT_SKIP &&
                    !Acceptance.baseMode(s[Keys.Wallpaper.displayMode], 0).isFit,
            )
        },
    ).associateBy { it.id }

    private val plugins = PluginRegistry(app, scope) { id -> controller.invalidate(id) }
    private val pluginProviders = ConcurrentHashMap<String, PluginWallpaperProvider>()
    private val pendingPluginPush = ConcurrentHashMap.newKeySet<String>()

    private fun providerFor(id: String): WallpaperProvider? = builtIns[id] ?: plugins.find(id)?.let { info ->
        pluginProviders.compute(id) { _, old ->
            if (old != null && old.info == info) {
                old
            } else {
                PluginWallpaperProvider(
                    context = app,
                    info = info,
                    pendingPush = { if (id in pendingPluginPush) params.get(id) else null },
                    onPushed = { pendingPluginPush.remove(id) },
                    storeParams = { params.set(id, it) },
                )
            }
        }
    }

    /** Built-in providers and their names, in the dropdown's order. Before [providers], which reads it. */
    private val builtInNames: List<Pair<String, Int>> = listOf(
        WallpaperProviders.NONE to R.string.wallpaper_provider_none,
        WallpaperProviders.REDDIT to R.string.wallpaper_provider_reddit,
        WallpaperProviders.WIKIMEDIA to R.string.wallpaper_provider_wikimedia,
        WallpaperProviders.DYNAMIC_COLORS to R.string.wallpaper_provider_dynamic_colors,
        WallpaperProviders.DEFAULT to R.string.wallpaper_provider_default,
        WallpaperProviders.MEDIA_FILE to R.string.wallpaper_provider_media_file,
        WallpaperProviders.FOLDER to R.string.wallpaper_provider_folder,
        WallpaperProviders.FOCUSED_PROGRAM to R.string.wallpaper_provider_focused_program,
        WallpaperProviders.NOW_PLAYING to R.string.wallpaper_provider_now_playing,
        WallpaperProviders.RANDOM_PROGRAM to R.string.wallpaper_provider_random_program,
        WallpaperProviders.THEME to R.string.wallpaper_provider_theme,
    )

    /** Every entry of the *Launcher wallpaper* dropdown: the built-ins in Projectivy's order, then plugins. */
    val providers: StateFlow<List<ProviderInfo>> = plugins.plugins
        .map { list -> builtInInfos() + list.map { ProviderInfo(it.uuid, it.name, isPlugin = true, packageName = it.packageName, settingsActivity = it.settingsActivity) } }
        .stateIn(scope, SharingStarted.Eagerly, builtInInfos())

    private fun builtInInfos(): List<ProviderInfo> = builtInNames.map { (id, res) -> ProviderInfo(id, app.getString(res)) }

    /** [info]'s name in the current language: [providers] keeps the one from when it was built. */
    fun displayName(info: ProviderInfo): String =
        if (info.isPlugin) info.name else builtInNames.firstOrNull { it.first == info.id }?.let { app.getString(it.second) } ?: info.name

    // ---- Engine ----

    private val engineConfig: Flow<EngineConfig> = combine(settings.settings, params.params, plugins.plugins, plugins.discovered) { s, p, pl, discovered ->
        val id = s[Keys.Wallpaper.provider]
        val builtIn = id in builtIns
        EngineConfig(
            providerId = id,
            // A plugin keeps its own preferences; its stored copy only travels with backups.
            params = if (builtIn) p[id] ?: ProviderParamsStore.default(id) else "",
            updateDelaySeconds = s[Keys.Wallpaper.updateDelay],
            ignoreUpdateOnVideo = s[Keys.Wallpaper.ignoreUpdateOnVideo],
            displayMode = s[Keys.Wallpaper.displayMode],
            wrongFormatMode = s[Keys.Wallpaper.wrongFormatMode],
            minAcceptedWidth = s[Keys.Wallpaper.minAcceptedWidth],
            providerPending = !builtIn && !discovered && pl.none { it.uuid == id },
        )
    }

    private val downloads = DownloadCache(File(app.cacheDir, "wallpaper/downloads"), container.okHttp.newBuilder().cache(null).build())

    /** The update loop. See [WallpaperController] for what each call does. */
    val controller: WallpaperController = WallpaperController(
        scope = engineScope,
        config = engineConfig,
        providers = ::providerFor,
        loader = AndroidWallpaperLoader(app, downloads, ::screenSize),
        clock = SystemClock::elapsedRealtime,
        lastWallpaper = SettingsLastWallpaper(settings),
        io = Dispatchers.IO,
        onSeed = { container.theme.offerSeed(it) },
        describeError = ::describe,
    )

    /** Video playback positions kept while the launcher is in the background. */
    val videoPositions = VideoPositionMemory()

    init {
        plugins.start()
        // Logging in (or the stored login being read at start) makes Reddit available: reload it.
        scope.launch {
            redditUser.map { it != null }.distinctUntilChanged().collect { loggedIn ->
                if (loggedIn) controller.invalidate(WallpaperProviders.REDDIT)
            }
        }
        // Now Playing changes become events; the provider's update mode decides who hears them.
        scope.launch {
            notificationSourceFlow
                .flatMapLatest { it?.nowPlaying ?: flowOf(null) }
                .distinctUntilChanged()
                .drop(1)
                .collect { np ->
                    controller.onEvent(WallpaperEvent.NowPlayingChanged(np?.isPlaying == true, np?.title, np?.artist, np?.album, np?.artUri))
                }
        }
    }

    // ---- Public API for the home screen and the settings pages ----

    /** The *Configure* page state of one provider: loading, count, error text, login needed. */
    fun status(providerId: String): Flow<ProviderStatus> =
        combine(controller.status(providerId), redditUser) { s, user ->
            if (providerId == WallpaperProviders.REDDIT) s.copy(needsLogin = user == null) else s
        }

    /**
     * Makes [info] the wallpaper source. Reddit is chosen like any other source; logged out, its
     * status says so until the *Login* button on its page is used.
     */
    fun selectProvider(info: ProviderInfo) {
        scope.launch {
            settings.set(Keys.Wallpaper.providerPackage, info.packageName)
            settings.set(Keys.Wallpaper.provider, info.id)
        }
    }

    /** The stored parameter of [providerId] (or its default). */
    fun paramsFlow(providerId: String): Flow<String> = params.flow(providerId)

    fun setParams(providerId: String, value: String): Job = scope.launch { params.set(providerId, value) }

    /** What a backup carries: provider id → parameter. */
    fun exportParams(): Map<String, String> = params.export()

    /** Restores parameters from a backup, keyed by provider id. Plugins receive theirs before their next request. */
    fun importParams(values: Map<String, String>, clearOthers: Boolean = false): Job = scope.launch {
        params.import(values, clearOthers)
        values.keys.filter { it !in builtIns }.forEach { pendingPluginPush += it }
        // Built-in parameters reload their source through the settings; a plugin needs a nudge.
        val current = settings.current[Keys.Wallpaper.provider]
        if (current !in builtIns) controller.invalidate(current)
    }

    // Wikimedia rows.

    fun wikimediaCategory(): Flow<WikimediaCategory> = params.flow(WallpaperProviders.WIKIMEDIA).map { WikimediaCategory.of(it) }

    /** The *Custom search* text row (enabled only for [WikimediaCategory.CUSTOM]). */
    fun wikimediaCustomSearch(): Flow<String> = params.flow(WallpaperProviders.WIKIMEDIA).map { WikimediaCategory.customText(it) }

    fun setWikimediaCategory(category: WikimediaCategory): Job = scope.launch {
        val id = WallpaperProviders.WIKIMEDIA
        val value = if (category == WikimediaCategory.CUSTOM) WikimediaCategory.customParam(params.customText(id)) else category.name
        params.set(id, value)
    }

    fun setWikimediaCustomSearch(text: String): Job = scope.launch {
        val id = WallpaperProviders.WIKIMEDIA
        params.setCustomText(id, text)
        params.set(id, WikimediaCategory.customParam(text))
    }

    // Reddit rows.

    /** The selected preset, or null for *[Custom subreddit]*. */
    fun redditPreset(): Flow<RedditPreset?> = params.flow(WallpaperProviders.REDDIT).map { RedditPresets.presetOf(it) }

    /** The *Custom subreddit* text row (enabled only when [redditPreset] is null). */
    fun redditCustomSubreddits(): Flow<String> = params.flow(WallpaperProviders.REDDIT).map { if (RedditPresets.presetOf(it) == null) it else "" }

    /** Selects a preset, or *[Custom subreddit]* with null (its last text comes back). */
    fun setRedditPreset(preset: RedditPreset?): Job = scope.launch {
        val id = WallpaperProviders.REDDIT
        params.set(id, preset?.subreddit ?: params.customText(id))
    }

    /** `+`-separated subreddit names. */
    fun setRedditCustomSubreddits(text: String): Job = scope.launch {
        val id = WallpaperProviders.REDDIT
        params.setCustomText(id, text)
        params.set(id, text)
    }

    /** The login screen; RESULT_OK when logged in. */
    fun redditLoginIntent(context: Context): Intent = RedditLoginActivity.intent(context)

    /** *Log out*: revokes the token, clears WebView cookies, the stored tokens and the cached list. */
    suspend fun redditLogout() {
        redditApi.revoke()
        withContext(Dispatchers.Main) {
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
            }
        }
        redditTokens.clear()
        redditCache.clear()
        controller.invalidate(WallpaperProviders.REDDIT)
    }

    /** The stored Reddit login, for a settings export; null while logged out. */
    suspend fun storedRedditLogin(): RedditTokens? = redditTokens.current()

    /**
     * Replaces the stored Reddit login with one from a backup, encrypted with this device's key;
     * null removes it from this device without revoking it, since it may be in use elsewhere.
     */
    suspend fun restoreRedditLogin(tokens: RedditTokens?) {
        if (tokens == null) redditTokens.clear() else redditTokens.save(tokens)
    }

    /** After a login: fetches the user name for the *Account* row. The source reloads by itself. */
    internal suspend fun onRedditLoggedIn() {
        runCatching { redditApi.me() }.getOrNull()?.let { redditTokens.setUsername(it) }
    }

    // Media file and folder rows.

    /**
     * Uses a file as the *Media file* wallpaper and switches to it: one from QLauncher's gallery
     * (a MediaStore or `file:` URI) or from the system picker. The file is not copied: a
     * persistable read grant is taken when the URI offers one.
     */
    fun chooseMediaFile(uri: Uri, mimeType: String?): Job = scope.launch {
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val mime = mimeType ?: app.contentResolver.getType(uri)
        val type = when {
            mime == null -> WallpaperType.IMAGE
            mime.startsWith("video/") -> WallpaperType.VIDEO
            mime == "application/json" -> WallpaperType.LOTTIE
            else -> WallpaperType.IMAGE
        }
        useAsMediaFile(Wallpaper(uri = uri.toString(), type = type))
    }

    /** *Media file* as a plain colour. */
    fun chooseMediaColor(argb: Int): Job = scope.launch {
        useAsMediaFile(Wallpaper(uri = "#%06X".format(argb and 0xFFFFFF), type = WallpaperType.COLOR))
    }

    /** A folder from the system folder picker (SAF tree URI) becomes the *Folder* source. */
    fun chooseFolder(treeUri: Uri): Job = scope.launch {
        runCatching { app.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        params.set(WallpaperProviders.FOLDER, treeUri.toString())
        settings.set(Keys.Wallpaper.providerPackage, "")
        // A new parameter or provider reloads the source by itself.
        settings.set(Keys.Wallpaper.provider, WallpaperProviders.FOLDER)
    }

    /**
     * *This wallpaper › Set as user wallpaper*: copies the current wallpaper into
     * `files/background` and makes it the *Media file* wallpaper. False when there is nothing to
     * keep (the default or dynamic wallpaper) or the copy failed.
     */
    suspend fun setAsUserWallpaper(): Boolean {
        val w = controller.wallpaper.value ?: return false
        if (w.isInternal) return false
        if (w.type == WallpaperType.COLOR) {
            useAsMediaFile(w)
            return true
        }
        val local = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(w.uri)
                when (uri.scheme) {
                    "http", "https" -> BackgroundFiles.copy(app, downloads.fetch(w.uri).inputStream(), extensionOf(w))
                    "rtsp", "rtspt", "rtspu" -> null
                    else -> BackgroundFiles.copy(app, uri)
                }
            }.getOrNull()
        }
        val stored = if (local != null) w.copy(uri = Uri.fromFile(local).toString()) else if (w.uri.startsWith("rtsp")) w else return false
        useAsMediaFile(stored)
        return true
    }

    /** `SetWallpaperActivity`: copies [uri] into `files/background` and makes it the *Media file* wallpaper. */
    suspend fun setFromExternal(uri: Uri, mimeType: String?): Boolean {
        val mime = mimeType ?: app.contentResolver.getType(uri)
        val file = withContext(Dispatchers.IO) { runCatching { BackgroundFiles.copy(app, uri, mime) }.getOrNull() } ?: return false
        val type = if (mime?.startsWith("video/") == true) WallpaperType.VIDEO else WallpaperType.IMAGE
        useAsMediaFile(Wallpaper(uri = Uri.fromFile(file).toString(), type = type, title = file.nameWithoutExtension))
        return true
    }

    private suspend fun useAsMediaFile(w: Wallpaper) {
        val value = if (w.type == WallpaperType.COLOR) w.uri else w.toJson()
        params.set(WallpaperProviders.MEDIA_FILE, value)
        BackgroundFiles.pruneExcept(app, Uri.parse(w.uri).takeIf { it.scheme == "file" }?.path)
        settings.set(Keys.Wallpaper.providerPackage, "")
        // A new parameter or provider reloads the source by itself.
        settings.set(Keys.Wallpaper.provider, WallpaperProviders.MEDIA_FILE)
    }

    private fun extensionOf(w: Wallpaper): String {
        val ext = Uri.parse(w.uri).lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return ext.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) } ?: if (w.type == WallpaperType.VIDEO) "mp4" else "jpg"
    }

    // This wallpaper rows.

    /** *View*: the web page about [wallpaper], or null when it has none. */
    fun viewIntent(wallpaper: Wallpaper): Intent? {
        val s = wallpaper.source?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
        return Intent(Intent.ACTION_VIEW, Uri.parse(s)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** The wallpaper's own action (Random program), or null. */
    fun actionIntent(wallpaper: Wallpaper): Intent? = wallpaper.actionUri?.let {
        runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }.getOrNull()
    }

    /** Starts [intent] if something handles it. */
    fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /** Called by the video renderer with colours sampled from frames. */
    internal fun offerSeed(argb: Int) = container.theme.offerSeed(argb)

    private fun describe(t: Throwable): String = when (t) {
        is ProviderException -> app.getString(t.messageRes)
        else -> app.getString(R.string.wallpaper_error_generic)
    }

    /** The screen in pixels, landscape. */
    private fun screenSize(): Pair<Int, Int> {
        val dm = app.resources.displayMetrics
        val w = maxOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(1)
        val h = minOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(1)
        return w to h
    }

    private class SettingsLastWallpaper(private val settings: SettingsRepository) : LastWallpaperStore {
        override fun read(): Wallpaper? = Wallpaper.fromJson(settings.current[Keys.Internal.lastWallpaper])
        override suspend fun write(wallpaper: Wallpaper) = settings.set(Keys.Internal.lastWallpaper, wallpaper.toJson())
    }

    private companion object {
        const val TAG = "QLWallpaper"
    }
}
