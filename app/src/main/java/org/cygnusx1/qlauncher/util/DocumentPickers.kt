/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Whether a real app answers the system document actions (open, create, pick a folder).
 *
 * Android TV builds without DocumentsUI (Google TV on the Onn 4K Pro, for one) route these actions
 * to `com.android.tv.frameworkpackagestubs`, whose activity only finishes with "cancelled". A
 * caller would then report "nothing chosen" when the truth is that no picker exists, so such a
 * stub does not count.
 */
object DocumentPickers {
    private const val STUB_PACKAGE = "com.android.tv.frameworkpackagestubs"

    fun available(context: Context, action: String = Intent.ACTION_OPEN_DOCUMENT, type: String = "*/*"): Boolean {
        val intent = Intent(action).apply {
            if (action != Intent.ACTION_OPEN_DOCUMENT_TREE) {
                addCategory(Intent.CATEGORY_OPENABLE)
                setType(type)
            }
        }
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .any { !isStub(it.activityInfo.packageName) }
    }

    fun isStub(packageName: String): Boolean = packageName == STUB_PACKAGE
}
