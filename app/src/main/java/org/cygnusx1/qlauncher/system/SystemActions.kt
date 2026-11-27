/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.system

import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import org.cygnusx1.qlauncher.MainActivity
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.apps.PmCompat
import org.cygnusx1.qlauncher.data.ItemKeys

/**
 * Small system helpers: the screensaver, the default launcher, permission checks and the system
 * settings pages QLauncher sends the user to. Each "open" call returns whether anything opened,
 * so the caller can show a fallback (for example the adb command) when the device lacks the page.
 */
class SystemActions(private val context: Context) {
    private val pm: PackageManager get() = context.packageManager

    // --- Screensaver ---

    /**
     * Whether the system screensaver can be started: a dream is configured
     * (`Settings.Secure.screensaver_components`) and System UI's dream starter resolves.
     */
    fun isScreensaverSupported(): Boolean {
        val components = Settings.Secure.getString(context.contentResolver, SCREENSAVER_COMPONENTS)
        if (components.isNullOrBlank()) return false
        return pm.resolveActivity(screensaverIntent(), 0) != null
    }

    /** Starts the system screensaver now. Works from an activity or the accessibility service. */
    fun startScreensaver(): Boolean = start(screensaverIntent())

    private fun screensaverIntent(): Intent = Intent(Intent.ACTION_MAIN)
        .setComponent(SOMNAMBULATOR)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // --- Default launcher ---

    /** True when QLauncher is the default home app. */
    fun isDefaultHome(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) return roles.isRoleHeld(RoleManager.ROLE_HOME)
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == context.packageName
    }

    /**
     * *Change default launcher*: the system's home settings (`ACTION_HOME_SETTINGS`), else the
     * HOME role request (API 29+, started for result from [activity] with [requestCode]).
     * Returns false when the device has neither, which is common on Android TV; the
     * accessibility service's launcher override is the fallback there.
     */
    fun openDefaultLauncherSettings(activity: Activity, requestCode: Int = REQUEST_HOME_ROLE): Boolean {
        if (startSettingsPage(Intent(Settings.ACTION_HOME_SETTINGS), activity)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = activity.getSystemService(RoleManager::class.java) ?: return false
            if (!roles.isRoleAvailable(RoleManager.ROLE_HOME) || roles.isRoleHeld(RoleManager.ROLE_HOME)) return false
            return try {
                @Suppress("DEPRECATION")
                activity.startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), requestCode)
                true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No HOME role request screen", e)
                false
            }
        }
        return false
    }

    // --- Permissions ---

    /** Whether READ_TV_LISTINGS (channels and programs) is granted. */
    fun isTvListingsGranted(): Boolean =
        context.checkSelfPermission(PERMISSION_READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED

    // --- Settings pages ---

    /**
     * Opens the system accessibility settings. (The per-service details page needs a permission
     * only system apps hold.) Returns false when the device has no such page, Fire OS among them;
     * show [accessibilityNoSettingsMessage] then.
     */
    fun openAccessibilitySettings(caller: Context = context): Boolean =
        startSettingsPage(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), caller)

    /**
     * What to say when [openAccessibilitySettings] finds no page. Fire OS has no way at all to
     * switch on another app's service, so a Fire TV is pointed at Home on Fire for the Home button.
     */
    @StringRes
    fun accessibilityNoSettingsMessage(): Int =
        if (pm.hasSystemFeature(FIRE_TV_FEATURE)) R.string.system_accessibility_fire_os else R.string.system_accessibility_no_settings

    /**
     * Opens the notification-access page, on QLauncher's entry when possible (API 30+). TV builds
     * often lack it: then this returns false and the caller shows [NOTIFICATION_ACCESS_ADB_COMMAND].
     */
    fun openNotificationAccessSettings(caller: Context = context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val details = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(context, QLNotificationListener::class.java).flattenToString(),
                )
            if (startSettingsPage(details, caller)) return true
        }
        return startSettingsPage(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), caller)
    }

    /**
     * [start] for a system settings page. Fire OS answers some standard settings actions, the
     * accessibility settings among them, with a compliance placeholder that shows nothing, so a
     * page only placeholders answer counts as missing. With no answer visible to QLauncher the
     * start is still tried, and [start] reports whether anything opened.
     */
    private fun startSettingsPage(intent: Intent, caller: Context): Boolean {
        val handlers = PmCompat.queryActivities(pm, intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (handlers.isNotEmpty() && handlers.all { isSettingsPlaceholder(it.activityInfo.packageName, it.activityInfo.name) }) {
            Log.i(TAG, "Only a placeholder answers ${intent.action}")
            return false
        }
        return start(intent, caller)
    }

    // --- Launching ---

    /**
     * Starts the app an item key names (`app:<package>/<class>`, or the bare `package/class` an
     * older value may hold), falling back to the package's TV or phone launch intent.
     */
    fun launchAppItem(itemKey: String, caller: Context = context): Boolean {
        val (pkg, cls) = ItemKeys.parseApp(itemKey) ?: parseBareComponent(itemKey) ?: return false
        val explicit = Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(pkg, cls))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (start(explicit, caller)) return true
        return launchPackage(pkg, caller)
    }

    /** Starts [packageName]'s TV launch activity, or its phone one. */
    fun launchPackage(packageName: String, caller: Context = context): Boolean {
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName) ?: return false
        return start(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), caller)
    }

    /**
     * Brings QLauncher's home screen to the front, as the HOME key would. The intent carries
     * `android.intent.extra.FROM_HOME_KEY` so the home screen can treat it as a Home press
     * (focus back to the default category).
     */
    fun openLauncher(caller: Context = context, extras: Intent? = null): Boolean {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            .putExtra(EXTRA_FROM_HOME_KEY, true)
        if (extras != null) intent.putExtras(extras)
        return start(intent, caller)
    }

    /** `startActivity` that reports instead of throwing when nothing answers. */
    fun start(intent: Intent, caller: Context = context): Boolean {
        if (caller !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            caller.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.i(TAG, "Nothing answers ${intent.action ?: intent.component}")
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to start ${intent.action ?: intent.component}", e)
            false
        }
    }

    private fun parseBareComponent(value: String): Pair<String, String>? {
        if (value.contains(':')) return null
        val slash = value.indexOf('/')
        if (slash <= 0 || slash == value.length - 1) return null
        val pkg = value.substring(0, slash)
        val cls = value.substring(slash + 1).let { if (it.startsWith('.')) pkg + it else it }
        return pkg to cls
    }

    companion object {
        private const val TAG = "QLSystemActions"

        const val SCREENSAVER_COMPONENTS = "screensaver_components"
        val SOMNAMBULATOR = ComponentName("com.android.systemui", "com.android.systemui.Somnambulator")
        const val PERMISSION_READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"
        const val EXTRA_FROM_HOME_KEY = "android.intent.extra.FROM_HOME_KEY"
        const val REQUEST_HOME_ROLE = 0x51A1

        /** Shown when the device has no notification-access screen. */
        const val NOTIFICATION_ACCESS_ADB_COMMAND =
            "adb shell cmd notification allow_listener org.cygnusx1.qlauncher/org.cygnusx1.qlauncher.system.QLNotificationListener"

        private const val FIRE_TV_FEATURE = "amazon.hardware.fire_tv"
        private const val FIRE_OS_SETTINGS = "com.amazon.tv.settings.v2"

        /** Fire OS's stand-ins for settings pages it lacks, such as `.compliance.CTSDummyIntentHandler`. */
        internal fun isSettingsPlaceholder(packageName: String, className: String): Boolean =
            packageName == FIRE_OS_SETTINGS && className.startsWith("$FIRE_OS_SETTINGS.compliance.CTSDummy")
    }
}
