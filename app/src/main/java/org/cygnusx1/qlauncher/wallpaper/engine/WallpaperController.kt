/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cygnusx1.qlauncher.settings.WallpaperProviders
import org.cygnusx1.qlauncher.wallpaper.UpdateMode
import org.cygnusx1.qlauncher.wallpaper.Wallpaper
import org.cygnusx1.qlauncher.wallpaper.WallpaperEvent
import org.cygnusx1.qlauncher.wallpaper.WallpaperType
import org.cygnusx1.qlauncher.wallpaper.source.ProviderStatus
import org.cygnusx1.qlauncher.wallpaper.source.WallpaperProvider

/** The settings the update loop depends on. */
data class EngineConfig(
    val providerId: String,
    val params: String,
    /** Seconds between changes; 0 = load once and never change automatically. */
    val updateDelaySeconds: Int,
    val ignoreUpdateOnVideo: Boolean,
    val displayMode: Int,
    val wrongFormatMode: Int,
    val minAcceptedWidth: Int,
    /** True while the provider cannot be looked up yet (plugins are still being discovered). */
    val providerPending: Boolean = false,
)

/** Where the last shown wallpaper is kept between starts (`Keys.Internal.lastWallpaper`). */
interface LastWallpaperStore {
    fun read(): Wallpaper?
    suspend fun write(wallpaper: Wallpaper)
}

/**
 * The wallpaper update loop.
 *
 * - Items are taken in list order, `(index + 1) % size`, wrapping round; changing provider (or its
 *   parameter) resets the index.
 * - Events are filtered by the provider's update mode, debounced 200 ms, then the provider is
 *   asked for its list (or the cached list is used while it is younger than the provider's cache
 *   time and the event is the timer).
 * - The timer fires every *Update frequency* seconds while at least one host is started. 0 loads
 *   once and never changes. On return the next change comes when the remaining time is up, but
 *   never sooner than 5 s.
 * - A failed load moves to the next item, five retries at most, then LoadingError and the default.
 * - With the *Skip* policy, a wrong-format item moves on in the same way; when nothing in the
 *   round fits, the first one passed over is shown (blurred) instead of the default.
 * - A new wallpaper is never shown sooner than 0.8 s after the previous change.
 * - The last wallpaper is stored and shown at once on the next start.
 *
 * All state lives on [scope], which must be single-threaded (a `limitedParallelism(1)` dispatcher
 * in the app, a test dispatcher in tests); public functions only post work to it.
 */
@OptIn(FlowPreview::class)
class WallpaperController(
    private val scope: CoroutineScope,
    config: Flow<EngineConfig>,
    private val providers: (String) -> WallpaperProvider?,
    private val loader: WallpaperLoader,
    private val clock: () -> Long,
    private val lastWallpaper: LastWallpaperStore,
    private val io: CoroutineDispatcher,
    private val onSeed: (Int) -> Unit = {},
    private val describeError: (Throwable) -> String? = { it.message },
) {
    private val _displayed = MutableStateFlow<DisplayedWallpaper?>(null)

    /** The wallpaper the host draws, with its decoded content. */
    val displayed: StateFlow<DisplayedWallpaper?> = _displayed.asStateFlow()

    private val _wallpaper = MutableStateFlow<Wallpaper?>(null)

    /** The wallpaper on screen, for *This wallpaper*. */
    val wallpaper: StateFlow<Wallpaper?> = _wallpaper.asStateFlow()

    private val _items = MutableStateFlow<List<Wallpaper>>(emptyList())

    /** The current provider's list: the *Show wallpapers* gallery and its count. */
    val items: StateFlow<List<Wallpaper>> = _items.asStateFlow()

    private val _status = MutableStateFlow<Map<String, ProviderStatus>>(emptyMap())

    /** Loading state, count and last error per provider id. */
    val status: StateFlow<Map<String, ProviderStatus>> = _status.asStateFlow()

    fun status(providerId: String): Flow<ProviderStatus> =
        _status.map { it[providerId] ?: ProviderStatus() }.distinctUntilChanged()

    private val events = MutableSharedFlow<WallpaperEvent>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // State below is only touched on [scope].
    private var cfg: EngineConfig? = null
    private var provider: WallpaperProvider? = null

    @Volatile
    private var providerUpdateMode: Int = UpdateMode.TIME_ELAPSED
    private var list: List<Wallpaper> = emptyList()
    private var listAt: Long? = null
    private var index = -1
    private var lastChangeAt: Long? = null
    private var deadline: Long? = null
    private var timerJob: Job? = null
    private var changeJob: Job? = null
    private var startCount = 0
    private var failureStreak = 0

    init {
        scope.launch {
            events.debounce(EVENT_DEBOUNCE_MS).collect { handleEvent(it) }
        }
        scope.launch {
            config.distinctUntilChanged().collect { onConfig(it) }
        }
    }

    // ---- Public API (thread-safe: everything is posted to [scope]) ----

    /** Feeds an event from the home screen (card focus, program focus, idle mode). */
    fun onEvent(event: WallpaperEvent) {
        if (event is WallpaperEvent.LoadingError) return
        if (!event.reaches(providerUpdateMode)) return
        events.tryEmit(event)
    }

    /** *This wallpaper › Next*: the next item now; restarts the timer. */
    fun next() {
        scope.launch { change { advance(WallpaperEvent.TimeElapsed); restartTimer() } }
    }

    /** Shows [wallpaper] from the gallery at once. The rotation position and the timer are left alone. */
    fun show(wallpaper: Wallpaper) {
        scope.launch {
            change {
                val content = load(wallpaper) ?: return@change
                publish(wallpaper, content, picked = true)
            }
        }
    }

    /** A host became visible; renderers resume and the timer runs while any host is started. */
    fun onStart() {
        scope.launch {
            startCount++
            if (startCount == 1) {
                val now = clock()
                val delayMs = (cfg?.updateDelaySeconds ?: 0) * 1000L
                deadline = maxOf(deadline ?: (now + delayMs), now + RESUME_MIN_DELAY_MS)
                scheduleTimer()
            }
        }
    }

    /** A host stopped; the timer pauses when none is left. */
    fun onStop() {
        scope.launch {
            startCount = (startCount - 1).coerceAtLeast(0)
            if (startCount == 0) {
                timerJob?.cancel()
                timerJob = null
            }
        }
    }

    /** The host could not play [wallpaper] (a video error): move on as for a failed load. */
    fun reportFailure(wallpaper: Wallpaper) {
        scope.launch {
            if (!wallpaper.sameAs(_wallpaper.value)) return@launch
            failureStreak++
            if (failureStreak > MAX_RETRIES) {
                failureStreak = 0
                change { loadingError() }
            } else {
                change { advance(WallpaperEvent.TimeElapsed) }
            }
        }
    }

    /** The host started playing [wallpaper]; the failure streak ends. */
    fun reportPlaying(wallpaper: Wallpaper) {
        scope.launch { if (wallpaper.sameAs(_wallpaper.value)) failureStreak = 0 }
    }

    /** A video that plays once reached its end. */
    fun onVideoEnded(wallpaper: Wallpaper) {
        scope.launch {
            if (!wallpaper.sameAs(_wallpaper.value)) return@launch
            change { advance(WallpaperEvent.TimeElapsed); restartTimer() }
        }
    }

    /**
     * Forgets the cached list of [providerId] (its folder, plugin or login changed). When it is
     * the current provider, the list is fetched again once the change in progress is done; the
     * wallpaper changes unless the one on screen is still in the new list.
     */
    fun invalidate(providerId: String) {
        scope.launch {
            if (providerId != cfg?.providerId) return@launch
            listAt = null
            val running = changeJob?.takeIf { it.isActive }
            changeJob = scope.launch {
                running?.join()
                val p = provider ?: return@launch
                val fetched = fetch(p, WallpaperEvent.TimeElapsed)
                val at = fetched.indexOfFirst { it.sameAs(_wallpaper.value) }
                if (at >= 0) {
                    index = at
                } else {
                    index = -1
                    step(fetched, firstForProvider = true)
                }
                restartTimer()
            }
        }
    }

    // ---- Internals ----

    private fun change(block: suspend () -> Unit) {
        changeJob?.cancel()
        changeJob = scope.launch { block() }
    }

    private suspend fun onConfig(new: EngineConfig) {
        val old = cfg
        cfg = new
        when {
            old == null -> providerChanged(initial = true)
            old.providerId != new.providerId || old.params != new.params -> providerChanged(initial = false)
            old.providerPending != new.providerPending -> providerChanged(initial = true)
            old.updateDelaySeconds != new.updateDelaySeconds -> restartTimer()
        }
    }

    /**
     * A new provider or parameter: the index and the cached list are reset and the first item is
     * shown. On [initial] (start-up), the last wallpaper is shown at once and kept if it is still
     * in the provider's list.
     */
    private fun providerChanged(initial: Boolean) {
        val c = cfg ?: return
        val p = if (c.providerPending) null else providers(c.providerId) ?: providers(WallpaperProviders.DEFAULT)
        provider = p
        providerUpdateMode = p?.updateMode ?: 0
        list = emptyList()
        listAt = null
        index = -1
        _items.value = emptyList()
        deadline = null
        timerJob?.cancel()
        change {
            if (_wallpaper.value == null) {
                val last = lastWallpaper.read()
                if (last != null) {
                    val content = load(last)
                    if (content != null) publish(last, content, immediate = true)
                }
            }
            // A plugin that is still being discovered: keep the last wallpaper until it is known.
            if (p == null) {
                if (!c.providerPending) showDefault()
                return@change
            }
            val fetched = fetch(p, WallpaperEvent.TimeElapsed)
            val current = _wallpaper.value
            val at = fetched.indexOfFirst { it.sameAs(current) }
            if (initial && at >= 0) {
                index = at
            } else {
                step(fetched, firstForProvider = true)
            }
            restartTimer()
        }
    }

    private suspend fun handleEvent(event: WallpaperEvent) {
        val c = cfg ?: return
        if (!event.reaches(providerUpdateMode)) return
        if (event is WallpaperEvent.TimeElapsed && c.ignoreUpdateOnVideo && c.updateDelaySeconds > 0 &&
            _wallpaper.value?.type == WallpaperType.VIDEO
        ) {
            // The end of the video brings the next wallpaper.
            return
        }
        change {
            advance(event)
            if (event is WallpaperEvent.TimeElapsed) restartTimer()
        }
    }

    /** Fetches (or reuses) the list for [event] and shows the next item. */
    private suspend fun advance(event: WallpaperEvent) {
        val p = provider ?: return
        val now = clock()
        val fresh = event is WallpaperEvent.TimeElapsed && p.cacheMillis > 0 &&
            listAt?.let { now - it < p.cacheMillis } == true
        val l = if (fresh) list else fetch(p, event)
        step(l, firstForProvider = false)
    }

    /** Asks [p] for its list; on failure the previous list stays. */
    private suspend fun fetch(p: WallpaperProvider, event: WallpaperEvent): List<Wallpaper> {
        val params = cfg?.params.orEmpty()
        setStatus(p.id) { it.copy(loading = true) }
        try {
            val fetched = withContext(io) { p.getWallpapers(event, params) }
            if (p !== provider) return fetched
            list = fetched
            listAt = clock()
            if (index >= fetched.size) index = -1
            _items.value = fetched
            setStatus(p.id) { it.copy(loading = false, count = fetched.size, error = null) }
            return fetched
        } catch (e: CancellationException) {
            setStatus(p.id) { it.copy(loading = false) }
            throw e
        } catch (e: Exception) {
            setStatus(p.id) { it.copy(loading = false, count = list.size, error = describeError(e)) }
            return list
        }
    }

    /** The first item of a round that the *Skip* policy passed over, already decoded. */
    private class Skipped(val index: Int, val wallpaper: Wallpaper, val content: WallpaperContent)

    /**
     * Shows the next acceptable item of [l]. An empty list shows the default wallpaper when the
     * provider has shown nothing yet, and otherwise leaves the screen alone.
     *
     * With the *Skip* policy, wrong-format items are passed over. When a round finds nothing that
     * fits, the first one passed over is shown anyway (see [settle]) rather than the default.
     */
    private suspend fun step(l: List<Wallpaper>, firstForProvider: Boolean) {
        if (l.isEmpty()) {
            if (firstForProvider || _wallpaper.value == null) showDefault()
            return
        }
        val c = cfg ?: return
        var failures = 0
        var sameSkips = 0
        var visited = 0
        var skipped: Skipped? = null
        while (true) {
            val s = skipped
            if (s != null && (visited >= l.size || failures > MAX_RETRIES)) {
                settle(s, l, c)
                return
            }
            index = (index + 1) % l.size
            visited++
            val w = l[index]
            if (w.sameAs(_wallpaper.value)) {
                // Nothing to change for a one-item list; otherwise look further.
                if (l.size == 1 || ++sameSkips >= l.size) return
                continue
            }
            val content = load(w)
            if (content != null) {
                val placement = Acceptance.place(content.width, content.height, c.displayMode, c.wrongFormatMode, c.minAcceptedWidth, w.displayMode)
                if (placement is Placement.Draw) {
                    failureStreak = 0
                    publish(w, content)
                    return
                }
                if (skipped == null) skipped = Skipped(index, w, content)
            }
            failures++
            if (failures > MAX_RETRIES && skipped == null) {
                loadingError()
                return
            }
        }
    }

    /**
     * A round of [l] found nothing that fits the *Skip* policy. The picture on screen stays when
     * it is an acceptable item of [l]; otherwise [s] is shown, drawn with
     * [Acceptance.SKIP_FALLBACK], and the rotation goes on after it.
     */
    private suspend fun settle(s: Skipped, l: List<Wallpaper>, c: EngineConfig) {
        val current = _displayed.value
        if (current != null && l.any { it.sameAs(current.wallpaper) }) {
            val cc = current.content
            val placement = Acceptance.place(cc.width, cc.height, c.displayMode, c.wrongFormatMode, c.minAcceptedWidth, current.wallpaper.displayMode)
            if (placement is Placement.Draw) return
        }
        index = s.index
        failureStreak = 0
        publish(s.wallpaper, s.content)
    }

    private suspend fun loadingError() {
        val p = provider
        if (p != null) {
            runCatching { withContext(io) { p.getWallpapers(WallpaperEvent.LoadingError, cfg?.params.orEmpty()) } }
        }
        showDefault()
    }

    private suspend fun showDefault() {
        if (Wallpaper.DEFAULT.sameAs(_wallpaper.value)) return
        val content = load(Wallpaper.DEFAULT) ?: WallpaperContent.Solid(0xFF000000.toInt())
        publish(Wallpaper.DEFAULT, content)
    }

    private suspend fun load(w: Wallpaper): WallpaperContent? = try {
        withContext(io) { loader.load(w) }.getOrNull()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun publish(w: Wallpaper, content: WallpaperContent, immediate: Boolean = false, picked: Boolean = false) {
        val last = lastChangeAt
        if (!immediate && last != null) {
            val wait = last + MIN_CHANGE_INTERVAL_MS - clock()
            if (wait > 0) delay(wait)
        }
        sequence++
        _displayed.value = DisplayedWallpaper(w, content, sequence, picked)
        _wallpaper.value = w
        lastChangeAt = clock()
        content.seed?.let(onSeed)
        scope.launch(io) { runCatching { lastWallpaper.write(w) } }
    }

    private var sequence = 0L

    /** The next timer tick comes a full period from now. */
    private fun restartTimer() {
        val c = cfg ?: return
        deadline = if (c.updateDelaySeconds > 0) clock() + c.updateDelaySeconds * 1000L else null
        scheduleTimer()
    }

    private fun scheduleTimer() {
        timerJob?.cancel()
        timerJob = null
        val c = cfg ?: return
        if (c.updateDelaySeconds <= 0 || startCount <= 0) return
        if (providerUpdateMode and UpdateMode.TIME_ELAPSED == 0) return
        val due = deadline ?: (clock() + c.updateDelaySeconds * 1000L).also { deadline = it }
        timerJob = scope.launch {
            val wait = due - clock()
            if (wait > 0) delay(wait)
            deadline = null
            events.emit(WallpaperEvent.TimeElapsed)
        }
    }

    private fun setStatus(id: String, f: (ProviderStatus) -> ProviderStatus) {
        _status.update { it + (id to f(it[id] ?: ProviderStatus())) }
    }

    companion object {
        const val EVENT_DEBOUNCE_MS = 200L
        const val MIN_CHANGE_INTERVAL_MS = 800L
        const val RESUME_MIN_DELAY_MS = 5_000L

        /** Retries with the next item after a failed load before falling back to the default. */
        const val MAX_RETRIES = 5
    }
}
