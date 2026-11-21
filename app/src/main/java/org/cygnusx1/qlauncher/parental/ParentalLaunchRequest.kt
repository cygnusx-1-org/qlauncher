/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.parental

import android.content.Context
import android.content.Intent
import org.cygnusx1.qlauncher.MainActivity

/**
 * A locked app reached the foreground without going through the home screen (a notification, a
 * voice search, another launcher...). The accessibility service brings `MainActivity` to the front
 * with these extras; the home screen shows [ParentalPrompt] in [ParentalPromptMode.Verify] mode
 * and, on success, starts [packageName] again (`SystemActions.launchPackage`).
 */
data class ParentalLaunchRequest(val packageName: String, val className: String?) {
    fun toIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        .putExtra(EXTRA_PACKAGE, packageName)
        .putExtra(EXTRA_CLASS, className)

    companion object {
        /** String extra: the locked app's package. Its presence means "ask for the parental code". */
        const val EXTRA_PACKAGE = "org.cygnusx1.qlauncher.extra.PARENTAL_CHECK_PACKAGE"

        /** String extra (optional): the activity class that was seen. */
        const val EXTRA_CLASS = "org.cygnusx1.qlauncher.extra.PARENTAL_CHECK_CLASS"

        /** Reads the request from an intent `MainActivity` received; null when it carries none. */
        fun from(intent: Intent?): ParentalLaunchRequest? {
            val pkg = intent?.getStringExtra(EXTRA_PACKAGE)?.takeIf { it.isNotBlank() } ?: return null
            return ParentalLaunchRequest(pkg, intent.getStringExtra(EXTRA_CLASS))
        }

        /** Removes the extras once handled, so a configuration change does not ask again. */
        fun consume(intent: Intent?) {
            intent?.removeExtra(EXTRA_PACKAGE)
            intent?.removeExtra(EXTRA_CLASS)
        }
    }
}
