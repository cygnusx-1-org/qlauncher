/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import org.cygnusx1.qlauncher.ui.LocaleHelper

class QLauncherApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // First, so a crash anywhere after this is recorded (when the user allowed it).
        org.cygnusx1.qlauncher.reports.ErrorReports.install(container.reports)
        Integration.install(this)
        LocaleHelper.reapply(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        LocaleHelper.reapply(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader
}

/** The process-wide container. */
val Context.container: AppContainer get() = (applicationContext as QLauncherApp).container
