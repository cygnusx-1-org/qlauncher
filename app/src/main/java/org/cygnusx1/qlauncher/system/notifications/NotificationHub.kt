/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.system.notifications

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cygnusx1.qlauncher.reports.ErrorReports
import org.cygnusx1.qlauncher.shared.MediaAction
import org.cygnusx1.qlauncher.shared.NotificationItem
import org.cygnusx1.qlauncher.shared.NotificationSource
import org.cygnusx1.qlauncher.shared.NowPlaying
import org.cygnusx1.qlauncher.system.QLNotificationListener
import org.cygnusx1.qlauncher.system.SystemActions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The process-wide [NotificationSource]. It exists whether or not [QLNotificationListener] is
 * bound: the listener attaches itself while connected, and the lists are empty otherwise.
 * [nowPlaying] comes from the media sessions, which only need the access to be granted, and falls
 * back to a media notification when no session says anything.
 */
class NotificationHub(
    private val context: Context,
    private val scope: CoroutineScope,
    private val actions: SystemActions,
) : NotificationSource {
    private val component = ComponentName(context, QLNotificationListener::class.java)
    private val pm: PackageManager = context.packageManager
    private val main = Handler(Looper.getMainLooper())
    private val icons = ImageCache(context.cacheDir, "system_notification_icons", ICON_MAX_PX)
    private val labels = ConcurrentHashMap<String, String>()
    private val systemApps = ConcurrentHashMap<String, Boolean>()

    private val _accessGranted = MutableStateFlow(readAccess())
    override val accessGranted: StateFlow<Boolean> = _accessGranted.asStateFlow()

    private val _notifications = MutableStateFlow<List<NotificationItem>>(emptyList())
    override val notifications: StateFlow<List<NotificationItem>> = _notifications.asStateFlow()

    private val mediaFallback = MutableStateFlow<NowPlaying?>(null)
    private val tracker = NowPlayingTracker(context, component, scope)

    override val nowPlaying: StateFlow<NowPlaying?> = combine(tracker.state, mediaFallback) { session, notification ->
        session ?: notification
    }.stateIn(scope, SharingStarted.Eagerly, null)

    @Volatile
    private var listener: QLNotificationListener? = null

    /** The notifications behind the current list, by key. */
    @Volatile
    private var records: Map<String, Record> = emptyMap()

    private val changes = MutableStateFlow(0)
    private val changeCounter = AtomicInteger()

    private class Record(val contentIntent: PendingIntent?, val autoCancel: Boolean)

    init {
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(ENABLED_LISTENERS), false,
            object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) = refreshAccess()
            },
        )
        scope.launch(Dispatchers.Main) {
            accessGranted.collect { granted -> if (granted) tracker.start() else tracker.stop() }
        }
        scope.launch(Dispatchers.Default) {
            changes.collectLatest {
                delay(REBUILD_DEBOUNCE_MILLIS)
                rebuild()
            }
        }
    }

    /** Re-reads whether access is granted; call it when the home screen resumes. */
    fun refreshAccess() {
        _accessGranted.value = readAccess()
        main.post { tracker.refresh() }
    }

    /** Drops cached app labels (the language changed). */
    fun clearLabelCache() {
        labels.clear()
        requestRebuild()
    }

    // --- Called by QLNotificationListener ---

    internal fun attach(l: QLNotificationListener) {
        listener = l
        _accessGranted.value = true
        requestRebuild()
    }

    internal fun detach(l: QLNotificationListener) {
        if (listener !== l) return
        listener = null
        refreshAccess()
        requestRebuild()
    }

    internal fun requestRebuild() {
        changes.value = changeCounter.incrementAndGet()
    }

    // --- NotificationSource ---

    override fun open(item: NotificationItem) {
        val record = records[item.key]
        val pi = record?.contentIntent
        if (pi != null) {
            try {
                pi.send(context, 0, null, null, null, null, sendOptions())
                if (record.autoCancel) listener?.cancelNotification(item.key)
                return
            } catch (e: PendingIntent.CanceledException) {
                Log.i(TAG, "Notification intent was cancelled; opening the app instead")
            }
        }
        actions.launchPackage(item.packageName)
    }

    override fun dismiss(item: NotificationItem) {
        listener?.cancelNotification(item.key)
    }

    override fun clearAll() {
        val keys = _notifications.value.filter { it.clearable }.map { it.key }
        if (keys.isNotEmpty()) listener?.cancelNotifications(keys.toTypedArray())
    }

    override fun mediaAction(action: MediaAction) {
        main.post { tracker.action(action) }
    }

    // --- Building the list ---

    private suspend fun rebuild() {
        val l = listener
        if (l == null) {
            records = emptyMap()
            _notifications.value = emptyList()
            mediaFallback.value = null
            withContext(Dispatchers.IO) { icons.retainOnly(emptyList()) }
            return
        }
        val active: Array<StatusBarNotification> = try {
            l.activeNotifications ?: emptyArray()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot read notifications", e)
            ErrorReports.recordError("notifications.read", e)
            return
        }
        val ranking = try {
            l.currentRanking
        } catch (e: RuntimeException) {
            null
        }
        val own = context.packageName
        val items = ArrayList<NotificationItem>()
        val newRecords = HashMap<String, Record>()
        val iconKeys = ArrayList<String>()
        var media: NowPlaying? = null
        val r = NotificationListenerService.Ranking()
        withContext(Dispatchers.IO) {
            for (sbn in active) {
                val n = sbn.notification ?: continue
                val extras = n.extras ?: Bundle.EMPTY
                val title = extras.getCharSequence(Notification.EXTRA_TITLE) ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)
                // Badges, and the app's switch for them, exist from Android 8.
                val canShowBadge = ranking == null || Build.VERSION.SDK_INT < 26 || !ranking.getRanking(sbn.key, r) || r.canShowBadge()
                val facts = NotificationFacts(
                    packageName = sbn.packageName,
                    tag = sbn.tag,
                    flags = n.flags,
                    title = title,
                    text = text,
                    canShowBadge = canShowBadge,
                    isMedia = extras.containsKey(Notification.EXTRA_MEDIA_SESSION) || n.category == Notification.CATEGORY_TRANSPORT,
                    isSystemApp = isSystemApp(sbn.packageName),
                )
                when (NotificationFilter.route(facts, own)) {
                    NotificationRoute.Hidden -> Unit
                    NotificationRoute.NowPlaying -> if (media == null) {
                        media = NowPlaying(
                            packageName = sbn.packageName,
                            title = title?.toString(),
                            artist = text?.toString(),
                            album = null,
                            artUri = iconFor(sbn, n, iconKeys),
                            isPlaying = true,
                            canPlayPause = false,
                            canStop = false,
                            canSkipPrevious = false,
                            canSkipNext = false,
                        )
                    }
                    NotificationRoute.Panel -> {
                        items += NotificationItem(
                            key = sbn.key,
                            packageName = sbn.packageName,
                            appLabel = labelOf(sbn.packageName),
                            title = title?.toString(),
                            text = text?.toString(),
                            postTime = sbn.postTime,
                            iconUri = iconFor(sbn, n, iconKeys),
                            contentIntent = null,
                            clearable = sbn.isClearable,
                        )
                        newRecords[sbn.key] = Record(
                            contentIntent = tvContentIntent(extras) ?: n.contentIntent,
                            autoCancel = n.flags and Notification.FLAG_AUTO_CANCEL != 0,
                        )
                    }
                }
            }
            icons.retainOnly(iconKeys)
        }
        items.sortByDescending { it.postTime }
        records = newRecords
        _notifications.value = items
        mediaFallback.value = media
    }

    /** The large icon unless the notification asks for the small one (Projectivy's rule), as a PNG. */
    private fun iconFor(sbn: StatusBarNotification, n: Notification, keys: MutableList<String>): String? {
        val large: Icon? = if (Build.VERSION.SDK_INT < 26 || n.badgeIconType != Notification.BADGE_ICON_SMALL) n.getLargeIcon() else null
        val icon = large ?: n.smallIcon ?: return null
        val key = "${sbn.key}|${sbn.postTime}|${if (large != null) "L" else "S"}"
        val file = icons.fileFor(key)
        keys += key
        if (file.isFile) return android.net.Uri.fromFile(file).toString()
        // A resource icon names its own package; loading it needs that package to be visible.
        val drawable = try {
            icon.loadDrawable(context)
        } catch (e: RuntimeException) {
            null
        } ?: return null
        return icons.put(key, drawable)
    }

    /** Android TV notifications may carry their own content intent in the TV extender bundle. */
    private fun tvContentIntent(extras: Bundle): PendingIntent? {
        val tv = extras.getBundle(TV_EXTENSIONS) ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            tv.getParcelable(TV_CONTENT_INTENT, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            tv.getParcelable(TV_CONTENT_INTENT) as? PendingIntent
        }
    }

    private fun labelOf(pkg: String): String = labels.getOrPut(pkg) {
        try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    private fun isSystemApp(pkg: String): Boolean = systemApps.getOrPut(pkg) {
        if (pkg == "android") return@getOrPut true
        try {
            pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
        } catch (e: PackageManager.NameNotFoundException) {
            // Not visible to QLauncher: only framework packages are assumed to be system.
            pkg.startsWith("com.android.")
        }
    }

    private fun readAccess(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 27) {
            context.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(component) == true
        } else {
            // The list the system keeps of granted listeners, as flattened component names.
            Settings.Secure.getString(context.contentResolver, ENABLED_LISTENERS).orEmpty()
                .split(':').any { ComponentName.unflattenFromString(it) == component }
        }
    } catch (e: RuntimeException) {
        false
    }

    /** Lets the notification's activity start: QLauncher is in front when the user picks it. */
    @Suppress("DEPRECATION")
    private fun sendOptions(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
        } else {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
        return ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode).toBundle()
    }

    private companion object {
        const val TAG = "QLNotifications"
        const val ENABLED_LISTENERS = "enabled_notification_listeners"
        const val TV_EXTENSIONS = "android.tv.EXTENSIONS"
        const val TV_CONTENT_INTENT = "content_intent"
        const val ICON_MAX_PX = 128
        const val REBUILD_DEBOUNCE_MILLIS = 150L
    }
}
