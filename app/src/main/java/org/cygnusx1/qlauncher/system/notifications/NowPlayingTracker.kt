/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.system.notifications

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cygnusx1.qlauncher.shared.MediaAction
import org.cygnusx1.qlauncher.shared.NowPlaying

/**
 * The active media session, from `MediaSessionManager.getActiveSessions(listener)`, which needs
 * notification-listener access (not a bound listener). A playing session wins; otherwise the
 * highest-priority session with metadata. Album art bitmaps are written to the cache as PNG.
 *
 * [start] and [stop] are called on the main thread; session callbacks arrive there too.
 */
class NowPlayingTracker(
    context: Context,
    private val listenerComponent: ComponentName,
    private val scope: CoroutineScope,
) {
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val art = ImageCache(context.cacheDir, "system_now_playing", ART_MAX_PX)

    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state.asStateFlow()

    private var started = false
    private var controllers: List<MediaController> = emptyList()
    @Volatile
    private var chosen: MediaController? = null
    private var publishJob: Job? = null

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = reselect()
        override fun onMetadataChanged(metadata: MediaMetadata?) = reselect()
        override fun onSessionDestroyed() = refresh()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> onSessions(list.orEmpty()) }

    fun start() {
        if (started || sessions == null) return
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, main)
            started = true
            refresh()
        } catch (e: SecurityException) {
            Log.i(TAG, "No notification access yet")
        }
    }

    fun stop() {
        if (!started) return
        started = false
        sessions?.removeOnActiveSessionsChangedListener(sessionsListener)
        onSessions(emptyList())
    }

    /** Re-reads the session list. */
    fun refresh() {
        if (!started || sessions == null) return
        val list = try {
            sessions.getActiveSessions(listenerComponent)
        } catch (e: SecurityException) {
            Log.i(TAG, "Notification access was revoked")
            emptyList()
        }
        onSessions(list)
    }

    fun action(action: MediaAction) {
        val c = chosen ?: return
        val controls = c.transportControls
        when (action) {
            MediaAction.PlayPause -> if (isPlaying(c.playbackState)) controls.pause() else controls.play()
            MediaAction.Stop -> controls.stop()
            MediaAction.Previous -> controls.skipToPrevious()
            MediaAction.Next -> controls.skipToNext()
        }
    }

    private fun onSessions(list: List<MediaController>) {
        val oldTokens = controllers.map { it.sessionToken }
        val newTokens = list.map { it.sessionToken }
        if (oldTokens != newTokens) {
            controllers.forEach { it.unregisterCallback(callback) }
            controllers = list
            list.forEach { it.registerCallback(callback, main) }
        }
        reselect()
    }

    private fun reselect() {
        val pick = controllers.firstOrNull { isPlaying(it.playbackState) }
            ?: controllers.firstOrNull { it.metadata?.let(::titleOf) != null && isActive(it.playbackState) }
        chosen = pick
        publish(pick)
    }

    private fun publish(c: MediaController?) {
        publishJob?.cancel()
        if (c == null) {
            _state.value = null
            return
        }
        val meta = c.metadata
        val pb = c.playbackState
        val actions = pb?.actions ?: 0L
        val base = NowPlaying(
            packageName = c.packageName,
            title = meta?.let(::titleOf),
            artist = meta?.let { m ->
                m.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            },
            album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            artUri = meta?.let(::artUriOf),
            isPlaying = isPlaying(pb),
            canPlayPause = actions and (PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE) != 0L,
            canStop = actions and PlaybackState.ACTION_STOP != 0L,
            canSkipPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canSkipNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
        )
        val bitmap = meta?.let {
            it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }
        if (bitmap == null || base.artUri != null) {
            _state.value = base
            return
        }
        // Every metadata read is a fresh parcel, so the bitmap itself cannot be the key.
        val key = listOf(base.packageName, base.title, base.artist, base.album, bitmap.width, bitmap.height).joinToString("|")
        val cached = art.fileFor(key)
        if (cached.isFile) {
            _state.value = base.copy(artUri = android.net.Uri.fromFile(cached).toString())
            return
        }
        _state.value = base
        publishJob = scope.launch {
            val uri = withContext(Dispatchers.IO) {
                art.put(key, bitmap).also { if (it != null) art.retainOnly(listOf(key)) }
            }
            if (uri != null && chosen === c) _state.value = base.copy(artUri = uri)
        }
    }

    private fun titleOf(m: MediaMetadata): String? =
        (m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))?.takeIf { it.isNotBlank() }

    private fun artUriOf(m: MediaMetadata): String? =
        listOf(MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            .firstNotNullOfOrNull { k -> m.getString(k)?.takeIf { it.isNotBlank() } }

    private companion object {
        const val TAG = "QLNowPlaying"
        const val ART_MAX_PX = 512

        fun isPlaying(pb: PlaybackState?): Boolean = when (pb?.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING, PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM, PlaybackState.STATE_CONNECTING,
            -> true
            else -> false
        }

        fun isActive(pb: PlaybackState?): Boolean = when (pb?.state) {
            null, PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR -> false
            else -> true
        }
    }
}
