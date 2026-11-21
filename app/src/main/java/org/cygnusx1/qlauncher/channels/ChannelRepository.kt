/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.channels

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.tv.TvContract
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cygnusx1.qlauncher.apps.PmCompat
import org.cygnusx1.qlauncher.data.ItemKeys
import org.cygnusx1.qlauncher.data.ItemOverrideStore
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.SettingsRepository
import org.cygnusx1.qlauncher.shared.Channel
import org.cygnusx1.qlauncher.shared.Program
import org.cygnusx1.qlauncher.shared.ProgramSource
import java.net.URISyntaxException
import java.util.concurrent.ConcurrentHashMap

/**
 * Channels, preview programs and Watch Next from the TV provider ([ProgramSource]).
 *
 * - [available]: `READ_TV_LISTINGS` is granted and the provider answered.
 * - [enabled]: *Enable channels*, which follows the permission until the user sets it.
 * - Content is read at start, whenever [enabled] or a program limit changes, on [refresh], and
 *   on provider changes while anything collects [channels], [watchNext] or a [programs] flow.
 *
 * Call [onResume] from the home activity's `onResume` so a permission granted in the system
 * dialog or settings is noticed.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ChannelRepository internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val overrides: ItemOverrideStore,
) : ProgramSource {
    private val reader = TvProviderReader(context.contentResolver)
    private val lock = Mutex()

    private val _permission = MutableStateFlow(hasPermission())
    private val _readable = MutableStateFlow(false)

    /** `READ_TV_LISTINGS` is granted. */
    val permissionGranted: StateFlow<Boolean> = _permission.asStateFlow()

    override val available: StateFlow<Boolean> = combine(_permission, _readable) { p, r -> p && r }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * The effective *Enable channels*. Its first value is computed at once (settings are loaded
     * synchronously), so the channel-category sync never sees a false "off" at start.
     */
    val enabled: StateFlow<Boolean> = combine(settings.settings, _permission) { s, p ->
        ChannelRules.isEnabled(s.isSet(Keys.General.enableChannels), s[Keys.General.enableChannels], p)
    }.distinctUntilChanged().stateIn(
        scope,
        SharingStarted.Eagerly,
        settings.current.let { ChannelRules.isEnabled(it.isSet(Keys.General.enableChannels), it[Keys.General.enableChannels], _permission.value) },
    )

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    private val _programs = MutableStateFlow<Map<Long, List<Program>>>(emptyMap())
    private val _watchNext = MutableStateFlow<List<Program>>(emptyList())
    private val programFlows = ConcurrentHashMap<Long, StateFlow<List<Program>>>()

    /** The last channel list read while enabled, null while disabled or unread (for category sync). */
    internal val loadedChannels = MutableStateFlow<List<Channel>?>(null)

    override val channels: StateFlow<List<Channel>> = _channels.asStateFlow()
    override val watchNext: StateFlow<List<Program>> = _watchNext.asStateFlow()

    override fun programs(channelId: Long): StateFlow<List<Program>> = programFlows.getOrPut(channelId) {
        _programs.map { it[channelId].orEmpty() }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), _programs.value[channelId].orEmpty())
    }

    init {
        scope.launch {
            combine(
                enabled,
                settings.flow(Keys.General.programsPerChannel),
                settings.flow(Keys.General.programsPerApp),
            ) { e, a, b -> Triple(e, a, b) }
                .distinctUntilChanged()
                .collect { refresh() }
        }
        scope.launch {
            val observed = combine(
                _channels.subscriptionCount,
                _programs.subscriptionCount,
                _watchNext.subscriptionCount,
            ) { a, b, c -> a + b + c > 0 }.distinctUntilChanged()
            combine(observed, enabled) { o, e -> o && e }
                .distinctUntilChanged()
                .flatMapLatest { watch -> if (watch) providerChanges() else emptyFlow() }
                .debounce(PROVIDER_DEBOUNCE_MS)
                .collect { refresh() }
        }
    }

    /** Re-checks the permission; refreshes when it changed. */
    fun onResume() {
        val now = hasPermission()
        if (now != _permission.value) {
            _permission.value = now
            requestRefresh()
        }
    }

    /** Fire-and-forget [refresh]. */
    fun requestRefresh() {
        scope.launch { refresh() }
    }

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        lock.withLock {
            _permission.value = hasPermission()
            if (!_permission.value) {
                _readable.value = false
                clear()
                return@withLock
            }
            val rows = reader.channels()
            _readable.value = rows != null
            if (rows == null || !enabled.value) {
                clear()
                return@withLock
            }
            val list = ChannelRules.channels(rows)
            val perChannel = settings.current[Keys.General.programsPerChannel]
            val perApp = settings.current[Keys.General.programsPerApp]
            val channelIds = list.mapTo(HashSet()) { it.id }
            val programs = reader.previewPrograms().orEmpty().filter { it.channelId in channelIds }
            _programs.value = ChannelRules.programsByChannel(programs, perChannel)
            _watchNext.value = ChannelRules.watchNext(reader.watchNext().orEmpty(), perApp)
            _channels.value = list
            loadedChannels.value = list
        }
    }

    private fun clear() {
        _channels.value = emptyList()
        _programs.value = emptyMap()
        _watchNext.value = emptyList()
        loadedChannels.value = null
    }

    override suspend fun allPreviewPrograms(): List<Program> {
        if (_channels.value.isEmpty()) refresh()
        return _programs.value.values.flatten()
    }

    /** A loaded program by id (preview or Watch Next). */
    fun program(id: Long): Program? =
        _watchNext.value.firstOrNull { it.id == id } ?: _programs.value.values.firstNotNullOfOrNull { l -> l.firstOrNull { it.id == id } }

    fun channel(id: Long): Channel? = _channels.value.firstOrNull { it.id == id }

    fun channelByUniqueId(uniqueId: String): Channel? = _channels.value.firstOrNull { it.uniqueId == uniqueId }

    /** The intent a program card opens, or null when its intent URI is unusable. */
    fun intentOf(program: Program): Intent? {
        val uri = program.intentUri ?: return null
        return try {
            Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (e: URISyntaxException) {
            null
        }
    }

    /** Opens a program (OK on a program card) and records the launch on its `program:` key. */
    fun open(context: Context, program: Program, options: Bundle? = null): Boolean {
        val intent = intentOf(program) ?: return false
        val ok = PmCompat.start(context, intent, options)
        if (ok) overrides.recordLaunch(ItemKeys.program(program.id))
        return ok
    }

    /** Asks the system to let a preview channel be shown (`TvContract.requestChannelBrowsable`). */
    fun requestBrowsable(channelId: Long): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        return try {
            TvContract.requestChannelBrowsable(context, channelId)
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "requestChannelBrowsable($channelId) refused", e)
            false
        }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun providerChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        val registered = try {
            context.contentResolver.registerContentObserver(TvProviderReader.ROOT, true, observer)
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot observe the TV provider", e)
            false
        }
        awaitClose { if (registered) context.contentResolver.unregisterContentObserver(observer) }
    }

    companion object {
        /** The runtime permission channels need. */
        const val PERMISSION = "android.permission.READ_TV_LISTINGS"
        private const val PROVIDER_DEBOUNCE_MS = 500L
        private const val TAG = "QLChannels"
    }
}
