/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.system

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.cygnusx1.qlauncher.container
import org.cygnusx1.qlauncher.system.notifications.NotificationHub

/**
 * The notification listener. It only forwards to the process-wide [NotificationHub]
 * (`container.system.notifications`), which builds the lists the home screen shows; nothing is
 * stored or sent anywhere.
 */
class QLNotificationListener : NotificationListenerService() {
    private val hub: NotificationHub get() = applicationContext.container.system.notificationHub

    override fun onListenerConnected() {
        super.onListenerConnected()
        hub.attach(this)
    }

    override fun onListenerDisconnected() {
        hub.detach(this)
        super.onListenerDisconnected()
        // The system unbinds a listener under memory pressure too; ask to come back while allowed.
        if (hub.accessGranted.value) {
            requestRebind(ComponentName(this, QLNotificationListener::class.java))
        }
    }

    override fun onDestroy() {
        hub.detach(this)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) = hub.requestRebuild()

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) = hub.requestRebuild()

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = hub.requestRebuild()
}
