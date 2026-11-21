/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.settings.pages

import android.content.Context
import androidx.annotation.StringRes
import org.cygnusx1.qlauncher.AppContainer
import org.cygnusx1.qlauncher.settings.BoolKey
import org.cygnusx1.qlauncher.settings.ChoiceKey
import org.cygnusx1.qlauncher.settings.ColorKey
import org.cygnusx1.qlauncher.settings.FloatKey
import org.cygnusx1.qlauncher.settings.IntKey
import org.cygnusx1.qlauncher.settings.LongKey
import org.cygnusx1.qlauncher.settings.SettingsRepository
import org.cygnusx1.qlauncher.settings.StringKey
import org.cygnusx1.qlauncher.ui.settings.model.Always
import org.cygnusx1.qlauncher.ui.settings.model.Option
import org.cygnusx1.qlauncher.ui.settings.model.Predicate
import org.cygnusx1.qlauncher.ui.settings.model.Row
import org.cygnusx1.qlauncher.ui.settings.model.RowContext
import org.cygnusx1.qlauncher.ui.settings.model.UiText
import org.cygnusx1.qlauncher.ui.settings.model.ValueFormat
import org.cygnusx1.qlauncher.ui.settings.model.text

/**
 * What page definitions may call: the container, navigation and the activity's actions. Pages are
 * plain data built from this; they hold no state of their own.
 */
class PageEnv(
    val context: Context,
    val container: AppContainer,
    val navigate: (String) -> Unit,
    val actions: SettingsActions,
) {
    val repo: SettingsRepository get() = container.settings
}

/** Actions a settings page triggers that need the activity or another subsystem. */
interface SettingsActions {
    fun toast(text: String)
    fun openDefaultLauncherSettings()
    fun openNotificationAccess()
    fun openAccessibilitySettings()
    fun setLanguage(tag: String)
    /** The list of backup files ([theme]: theme files) QLauncher finds without a file manager. */
    fun importFromFile(theme: Boolean)
    fun importFromAutoBackup()
    fun export(theme: Boolean, share: Boolean)
    fun resetAll()
    fun setParentalCode()
    fun requestTvListings()
    fun openWallpaperGallery()
    fun startActivity(intent: android.content.Intent)
    fun pickFolder(onPicked: (android.net.Uri) -> Unit)

    /** A picture, video or Lottie file from QLauncher's own gallery of the device's media (no file manager). */
    fun pickWallpaperFile(onPicked: (android.net.Uri, String?) -> Unit)

    /** A folder holding pictures or videos, from QLauncher's own list (no file manager). */
    fun pickWallpaperFolder(onPicked: (android.net.Uri) -> Unit)
    fun pickDocument(mimeTypes: Array<String>, onPicked: (android.net.Uri) -> Unit)

    /** Opens the colour picker for a value that is not a [org.cygnusx1.qlauncher.settings.ColorKey]. */
    fun pickColor(initial: Int, onPicked: (Int) -> Unit)

    /** Shows a calibration pattern full screen. */
    fun showPattern(index: Int)
}

// Row builders, so page definitions stay one line per row.

fun toggle(
    key: BoolKey,
    @StringRes title: Int,
    @StringRes desc: Int? = null,
    enabled: Predicate = Always,
    visible: Predicate = Always,
    description: ((RowContext) -> UiText?)? = desc?.let { d -> { _: RowContext -> text(d) } },
    effective: ((RowContext) -> Boolean)? = null,
) = Row.Toggle(key, text(title), description, enabled = enabled, visible = visible, effectiveValue = effective)

fun slider(
    key: IntKey,
    @StringRes title: Int,
    format: ValueFormat = ValueFormat.Plain,
    centred: Boolean = false,
    enabled: Predicate = Always,
    visible: Predicate = Always,
) = Row.Slider(intKey = key, title = text(title), format = format, centred = centred, enabled = enabled, visible = visible)

fun slider(
    key: FloatKey,
    @StringRes title: Int,
    format: ValueFormat = ValueFormat.Percent,
    centred: Boolean = false,
    enabled: Predicate = Always,
    visible: Predicate = Always,
) = Row.Slider(floatKey = key, title = text(title), format = format, centred = centred, enabled = enabled, visible = visible)

fun choice(
    env: PageEnv,
    key: ChoiceKey,
    @StringRes title: Int,
    labels: List<Int>,
    enabled: Predicate = Always,
    visible: Predicate = Always,
    /** Maps option position to stored value when the options are a subset. */
    values: List<Int> = labels.indices.toList(),
    optionEnabled: (Int) -> Boolean = { true },
) = Row.Dropdown(
    id = key.name,
    title = text(title),
    options = { _ -> labels.mapIndexed { i, l -> Option(text(l), enabled = optionEnabled(values[i])) } },
    selected = { ctx -> values.indexOf(ctx.settings[key]) },
    onSelect = { i -> env.repo.update(key, values[i]) },
    icon = key.icon,
    badges = key.badges,
    enabled = enabled,
    visible = visible,
)

fun color(
    key: ColorKey,
    @StringRes title: Int,
    @StringRes desc: Int? = null,
    allowDynamic: Boolean = true,
    enabled: Predicate = Always,
    visible: Predicate = Always,
) = Row.Color(key, text(title), desc?.let { d -> { _: RowContext -> text(d) } }, allowDynamic, enabled = enabled, visible = visible)

fun textEntry(
    key: StringKey,
    @StringRes title: Int,
    @StringRes empty: Int? = null,
    enabled: Predicate = Always,
    visible: Predicate = Always,
) = Row.TextEntry(key, text(title), empty?.let { text(it) }, enabled = enabled, visible = visible)

fun time(
    key: LongKey,
    @StringRes title: Int,
    timeOfDay: Boolean,
    enabled: Predicate = Always,
    visible: Predicate = Always,
) = Row.Time(key, text(title), timeOfDay, enabled = enabled, visible = visible)

fun nav(id: String, @StringRes title: Int, target: String, icon: String, @StringRes desc: Int? = null, visible: Predicate = Always, enabled: Predicate = Always) =
    Row.Nav(id, text(title), target, icon, desc?.let { d -> { _: RowContext -> text(d) } }, visible = visible, enabled = enabled)

fun action(id: String, @StringRes title: Int, onClick: () -> Unit, icon: String? = null) = Row.Action(id, text(title), onClick, icon = icon)
