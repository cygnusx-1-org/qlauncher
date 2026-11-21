/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.plugin

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.wallpaper.Wallpaper
import org.cygnusx1.qlauncher.wallpaper.WallpaperEvent
import org.cygnusx1.qlauncher.wallpaper.source.ProviderException
import org.cygnusx1.qlauncher.wallpaper.source.WallpaperProvider
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A discovered wallpaper plugin, from its service's meta-data. */
data class PluginInfo(
    val uuid: String,
    val name: String,
    val component: ComponentName,
    val settingsActivity: ComponentName?,
    val updateMode: Int,
    val cacheMillis: Long,
) {
    val packageName: String get() = component.packageName
}

/**
 * Finds wallpaper plugins: services answering [PluginApi.ACTION] whose meta-data has API version
 * 1 and a 36-character UUID. Rescans when packages change and honours the plugins' update
 * broadcast at most once per 5 s per provider.
 */
class PluginRegistry(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPluginUpdated: (providerId: String) -> Unit,
) {
    private val _plugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    val plugins: StateFlow<List<PluginInfo>> = _plugins.asStateFlow()

    private val _discovered = MutableStateFlow(false)

    /** True once the first scan has finished. */
    val discovered: StateFlow<Boolean> = _discovered.asStateFlow()

    private val lastUpdate = ConcurrentHashMap<String, Long>()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getStringExtra(PluginApi.EXTRA_PROVIDER_ID) ?: return
            if (_plugins.value.none { it.uuid == id }) return
            val now = SystemClock.elapsedRealtime()
            val last = lastUpdate[id]
            if (last != null && now - last < UPDATE_THROTTLE_MS) return
            lastUpdate[id] = now
            onPluginUpdated(id)
        }
    }

    fun start() {
        val pkgFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, packageReceiver, pkgFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(context, updateReceiver, IntentFilter(PluginApi.ACTION_PROVIDER_UPDATED), ContextCompat.RECEIVER_EXPORTED)
        refresh()
    }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            _plugins.value = discover()
            _discovered.value = true
        }
    }

    fun find(uuid: String): PluginInfo? = _plugins.value.firstOrNull { it.uuid == uuid }

    @Suppress("DEPRECATION")
    private fun discover(): List<PluginInfo> {
        val pm = context.packageManager
        val services = runCatching { pm.queryIntentServices(Intent(PluginApi.ACTION), PackageManager.GET_META_DATA) }.getOrDefault(emptyList())
        return services.mapNotNull { ri -> ri.serviceInfo?.let { parse(pm, it) } }
            .distinctBy { it.uuid }
            .sortedBy { it.name.lowercase() }
    }

    @Suppress("DEPRECATION")
    private fun parse(pm: PackageManager, si: ServiceInfo): PluginInfo? {
        if (!si.exported) return null
        val meta = si.metaData ?: return null
        val res = runCatching { pm.getResourcesForApplication(si.applicationInfo) }.getOrNull()
        fun string(key: String): String? = when (val v = meta.get(key)) {
            is String -> v
            is Int -> res?.let { r -> runCatching { r.getString(v) }.getOrNull() }
            null -> null
            else -> v.toString()
        }
        fun number(key: String): Long? = when (val v = meta.get(key)) {
            is Int -> if (looksLikeResId(v)) res?.let { r -> runCatching { r.getInteger(v).toLong() }.getOrNull() } ?: v.toLong() else v.toLong()
            is Long -> v
            is Float -> v.toLong()
            is String -> v.trim().toLongOrNull()
            else -> null
        }
        val api = number(PluginApi.META_API_VERSION)?.toInt() ?: return null
        if (api != PluginApi.SUPPORTED_API_VERSION) return null
        val uuid = string(PluginApi.META_UUID)?.trim() ?: return null
        if (uuid.length != 36) return null
        val name = string(PluginApi.META_NAME)?.takeIf { it.isNotBlank() } ?: si.loadLabel(pm).toString()
        val settings = string(PluginApi.META_SETTINGS_ACTIVITY)?.takeIf { it.isNotBlank() }?.let {
            val cls = if (it.startsWith(".")) si.packageName + it else it
            ComponentName(si.packageName, cls)
        }
        return PluginInfo(
            uuid = uuid,
            name = name,
            component = ComponentName(si.packageName, si.name),
            settingsActivity = settings,
            updateMode = number(PluginApi.META_UPDATE_MODE)?.toInt() ?: PluginApi.DEFAULT_UPDATE_MODE,
            cacheMillis = number(PluginApi.META_CACHE_MILLIS) ?: PluginApi.DEFAULT_CACHE_MILLIS,
        )
    }

    /** Resource ids have a package byte (0x7f for apps); plain numbers in meta-data do not. */
    private fun looksLikeResId(v: Int): Boolean = (v ushr 24) != 0

    companion object {
        const val UPDATE_THROTTLE_MS = 5_000L
    }
}

/**
 * A plugin as a [WallpaperProvider]: binds the service for each request, asks for the list, and
 * keeps the plugin's preference string (its parameter) so it travels with backups. Parameters
 * imported from a backup are pushed to the plugin once, before its next request.
 */
class PluginWallpaperProvider(
    private val context: Context,
    val info: PluginInfo,
    private val pendingPush: () -> String?,
    private val onPushed: () -> Unit,
    private val storeParams: suspend (String) -> Unit,
) : WallpaperProvider {
    override val id: String get() = info.uuid
    override val updateMode: Int get() = info.updateMode
    override val cacheMillis: Long get() = info.cacheMillis

    override suspend fun getWallpapers(event: WallpaperEvent, params: String): List<Wallpaper> {
        if (event is WallpaperEvent.LoadingError) return emptyList()
        return try {
            withTimeout(CALL_TIMEOUT_MS) {
                withBinder { proxy ->
                    pendingPush()?.let { proxy.setPreferences(it); onPushed() }
                    val list = proxy.getWallpapers(event)
                    val prefs = runCatching { proxy.getPreferences() }.getOrNull()
                    if (prefs != null && prefs != params) storeParams(prefs)
                    list
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw ProviderException(R.string.wallpaper_error_plugin, e)
        } catch (e: ProviderException) {
            throw e
        } catch (e: Exception) {
            throw ProviderException(R.string.wallpaper_error_plugin, e)
        }
    }

    private suspend fun <T> withBinder(block: suspend (WallpaperProviderServiceProxy) -> T): T {
        val holder = arrayOfNulls<ServiceConnection>(1)
        val binder: IBinder = suspendCancellableCoroutine { cont ->
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (service != null && cont.isActive) cont.resume(service)
                }

                override fun onServiceDisconnected(name: ComponentName?) = Unit

                override fun onNullBinding(name: ComponentName?) {
                    if (cont.isActive) cont.resumeWithException(ProviderException(R.string.wallpaper_error_plugin))
                }
            }
            holder[0] = connection
            val intent = Intent(PluginApi.ACTION).setComponent(info.component)
            val bound = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
            if (!bound) {
                runCatching { context.unbindService(connection) }
                holder[0] = null
                if (cont.isActive) cont.resumeWithException(ProviderException(R.string.wallpaper_error_plugin))
            }
            cont.invokeOnCancellation { holder[0]?.let { c -> runCatching { context.unbindService(c) } }; holder[0] = null }
        }
        try {
            return withContext(Dispatchers.IO) { block(WallpaperProviderServiceProxy(binder)) }
        } finally {
            holder[0]?.let { c -> runCatching { context.unbindService(c) } }
            holder[0] = null
        }
    }

    companion object {
        private const val CALL_TIMEOUT_MS = 15_000L
    }
}
