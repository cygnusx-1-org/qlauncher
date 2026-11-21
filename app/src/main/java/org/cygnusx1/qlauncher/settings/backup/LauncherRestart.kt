/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings.backup

import android.content.Context
import android.content.Intent
import org.cygnusx1.qlauncher.MainActivity
import kotlin.system.exitProcess

/** Restarts the launcher after an import or a reset, so every subsystem starts from the new state. */
object LauncherRestart {
    /** Relaunches [MainActivity] in a fresh task and ends this process. Call from the main thread. */
    fun restart(context: Context): Nothing {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(Intent.makeRestartActivityTask(intent.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Runtime.getRuntime().exit(0)
        exitProcess(0)
    }
}
