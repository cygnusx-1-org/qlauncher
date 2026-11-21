/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.install

import android.content.res.Resources
import org.cygnusx1.qlauncher.R

/** The line the *Install 3rd party apps* page shows for an [InstallState]; null for [InstallState.Idle]. */
object InstallMessages {
    fun describe(res: Resources, state: InstallState): String? = when (state) {
        InstallState.Idle -> null
        is InstallState.Downloading -> state.progress?.let {
            res.getString(R.string.apps_install_state_downloading, state.name, (it * 100).toInt())
        } ?: res.getString(R.string.apps_install_state_downloading_unknown, state.name)
        is InstallState.Installing -> res.getString(R.string.apps_install_state_installing, state.name)
        is InstallState.AwaitingConfirmation -> res.getString(R.string.apps_install_state_confirm, state.name)
        is InstallState.Success -> res.getString(R.string.apps_install_state_success, state.name)
        is InstallState.NeedsUnknownSources -> res.getString(R.string.apps_install_state_unknown_sources)
        is InstallState.HandedToDownloader -> res.getString(
            if (state.entered) R.string.apps_install_state_downloader_sent else R.string.apps_install_state_downloader_type,
            state.code,
        )
        is InstallState.OpenedStore -> res.getString(R.string.apps_install_state_store)
        is InstallState.OpenedInBrowser -> res.getString(R.string.apps_install_state_browser)
        is InstallState.Failed -> when (state.reason) {
            FailReason.InvalidInput -> res.getString(R.string.apps_install_fail_invalid)
            FailReason.NoUsbVolume -> res.getString(R.string.apps_install_fail_no_usb)
            FailReason.UnsupportedArchive -> res.getString(R.string.apps_install_fail_archive)
            FailReason.Network -> res.getString(R.string.apps_install_fail_network)
            FailReason.HttpError -> res.getString(R.string.apps_install_fail_http, state.detail.orEmpty())
            FailReason.NotAnApk -> res.getString(R.string.apps_install_fail_not_apk)
            FailReason.FileUnreadable -> res.getString(R.string.apps_install_fail_unreadable)
            FailReason.InstallFailed -> res.getString(R.string.apps_install_fail_install)
            FailReason.Cancelled -> res.getString(R.string.apps_install_fail_cancelled)
            FailReason.UnknownSourcesDenied -> res.getString(R.string.apps_install_fail_unknown_sources)
            FailReason.DownloaderMissing -> res.getString(R.string.apps_install_fail_downloader)
            FailReason.NoStore -> res.getString(R.string.apps_install_fail_store)
        }
    }
}
