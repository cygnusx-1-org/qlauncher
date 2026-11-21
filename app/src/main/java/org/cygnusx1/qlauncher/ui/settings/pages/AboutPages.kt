/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.settings.pages

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.cygnusx1.qlauncher.BuildConfig
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.container
import org.cygnusx1.qlauncher.data.AppJson
import org.cygnusx1.qlauncher.install.InstallMessages
import org.cygnusx1.qlauncher.install.InstallState
import org.cygnusx1.qlauncher.ui.settings.model.LeftPane
import org.cygnusx1.qlauncher.ui.settings.model.Page
import org.cygnusx1.qlauncher.ui.settings.model.Row
import org.cygnusx1.qlauncher.ui.settings.model.raw
import org.cygnusx1.qlauncher.ui.settings.model.text
import org.cygnusx1.qlauncher.update.UpdateChecker
import org.cygnusx1.qlauncher.update.UpdateState

/** *About › QLauncher* and *Libs / Credits*. */
object AboutPages {
    const val PROJECT_URL = "https://github.com/cygnusx-1-org/qlauncher"
    private const val LICENCE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

    @Serializable
    private data class Library(val name: String, val licence: String)

    fun register() {
        SettingsPages.register(PageIds.ABOUT_APP) { env, _ -> app(env) }
        SettingsPages.register(PageIds.ABOUT_LIBS) { env, _ -> libs(env) }
    }

    private fun app(env: PageEnv) = Page(
        PageIds.ABOUT_APP,
        text(R.string.settings_breadcrumb_about),
        text(R.string.app_name),
        raw(env.context.getString(R.string.about_version, BuildConfig.VERSION_NAME) + "\n" + env.context.getString(R.string.about_copyright)),
        icon = "info",
        leftPane = LeftPane.Qr(PROJECT_URL),
        rows = {
            val updates = env.context.container.updates
            val available = (updates.state.value as? UpdateState.Available)?.update
            listOf(
                Row.Action(
                    "check_updates", text(R.string.about_check_updates), { updates.checkNow() },
                    description = { _ ->
                        when (val st = updates.state.value) {
                            UpdateState.Idle -> null
                            UpdateState.Checking -> text(R.string.about_update_checking)
                            is UpdateState.UpToDate -> text(R.string.about_update_latest, BuildConfig.VERSION_NAME)
                            is UpdateState.Available -> text(R.string.about_update_available, st.update.versionName)
                            is UpdateState.Failed -> text(R.string.about_update_failed, st.reason)
                        }
                    },
                    icon = "update",
                ),
                Row.Action(
                    "install_update", text(R.string.about_update_install, available?.versionName.orEmpty()), { updates.install(env.context) },
                    description = { _ ->
                        val update = available ?: return@Action null
                        val install = env.context.container.apps.installer.state.value
                        val progress = InstallMessages.describe(env.context.resources, install)
                        if (progress != null && install.relatesTo(UpdateChecker.installName(update))) raw(progress) else raw(update.changelog.joinToString(" · "))
                    },
                    icon = "system_update",
                    visible = { _ -> available != null },
                ),
                Row.Nav("libs", text(R.string.about_libs), PageIds.ABOUT_LIBS, "library_books"),
                Row.Expander(
                    "links", text(R.string.about_links),
                    listOf(
                        Row.Action("link_project", text(R.string.about_link_project), { openUrl(env, PROJECT_URL) }),
                        Row.Action("link_licence", text(R.string.about_link_licence), { openUrl(env, LICENCE_URL) }),
                    ),
                    icon = "link",
                ),
                Row.Info("translation", text(R.string.about_translation), { text(R.string.about_translation_credit) }, icon = "translate"),
            )
        },
    )

    private fun libs(env: PageEnv): Page {
        val libraries = runCatching {
            env.context.assets.open("licenses/libraries.json").use { s ->
                AppJson.decodeFromString(ListSerializer(Library.serializer()), s.readBytes().decodeToString())
            }
        }.getOrDefault(emptyList())
        return Page(
            PageIds.ABOUT_LIBS, text(R.string.settings_breadcrumb_about), text(R.string.about_libs), icon = "library_books",
            rows = { libraries.mapIndexed { i, l -> Row.Info("lib$i", raw(l.name), { raw(l.licence) }) } },
        )
    }

    fun openUrl(env: PageEnv, url: String) = openUrl(env.context, url) { env.actions.toast(it) }

    fun openUrl(context: Context, url: String, toast: (String) -> Unit) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            toast(context.getString(R.string.about_no_browser, url))
        }
    }
}

/** Whether the installer's [this] state is about the install named [name]. */
private fun InstallState.relatesTo(name: String): Boolean = when (this) {
    is InstallState.Downloading -> this.name == name
    is InstallState.Installing -> this.name == name
    is InstallState.AwaitingConfirmation -> this.name == name
    is InstallState.Success -> this.name == name
    is InstallState.NeedsUnknownSources -> this.name == name
    is InstallState.Failed -> true
    else -> false
}
