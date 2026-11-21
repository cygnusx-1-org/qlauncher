/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.settings.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import org.cygnusx1.qlauncher.settings.Badge
import org.cygnusx1.qlauncher.settings.BoolKey
import org.cygnusx1.qlauncher.settings.ChoiceKey
import org.cygnusx1.qlauncher.settings.ColorKey
import org.cygnusx1.qlauncher.settings.FloatKey
import org.cygnusx1.qlauncher.settings.IntKey
import org.cygnusx1.qlauncher.settings.LongKey
import org.cygnusx1.qlauncher.settings.Settings
import org.cygnusx1.qlauncher.settings.StringKey

/** Text that is either a string resource or a literal. */
@Immutable
sealed interface UiText {
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val text: String) : UiText

    @Composable
    fun resolve(): String = when (this) {
        is Res -> if (args.isEmpty()) stringResource(id) else stringResource(id, *args.toTypedArray())
        is Raw -> text
    }
}

fun text(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())
fun raw(s: String): UiText = UiText.Raw(s)

/** What the left half of a guided page shows. */
@Immutable
sealed interface LeftPane {
    /** Icon tile, breadcrumb, title and description. */
    data object Info : LeftPane

    /** As [Info], with a QR code under the description. */
    data class Qr(val payload: String) : LeftPane

    /** The tilted, non-focusable live copy of the home screen. */
    data object LivePreview : LeftPane

    /** The wallpaper with the current picture adjustment. */
    data object Wallpaper : LeftPane

    /** Anything else, drawn by the page itself (calibration patterns, thumbnails). */
    class Custom(val wide: Boolean = false, val content: @Composable () -> Unit) : LeftPane
}

/** How a slider shows its value. */
enum class ValueFormat { Plain, Percent, MinutesSeconds, Letters, OneDecimal, Seconds }

/** Conditions rows depend on beyond the settings themselves (permissions, services, device). */
@Immutable
data class Environment(
    val accessibilityEnabled: Boolean = false,
    val notificationAccess: Boolean = false,
    val tvListingsGranted: Boolean = false,
    val tvListingsPermanentlyDenied: Boolean = false,
    val tvProviderAvailable: Boolean = true,
    val hasTvInputs: Boolean = false,
    val hasBattery: Boolean = false,
    val iconPackInstalled: Boolean = false,
    val sdk: Int = android.os.Build.VERSION.SDK_INT,
    /** Bumped when state outside the settings changes (wallpaper lists, profiles, installs). */
    val revision: Long = 0L,
)

/** Everything a row predicate may look at. */
@Immutable
data class RowContext(val settings: Settings, val env: Environment)

typealias Predicate = (RowContext) -> Boolean

val Always: Predicate = { true }

/** One option of a dropdown. */
@Immutable
data class Option(val label: UiText, val enabled: Boolean = true, val prefix: String? = null)

/**
 * A row of a guided page. Rows are data; one renderer draws every kind, so a row of a given kind
 * lands on the same pixels on every page and in every state.
 */
@Immutable
sealed interface Row {
    val id: String
    val title: UiText
    val icon: String?
    val badges: Set<Badge>
    val visible: Predicate
    val enabled: Predicate

    /** Opens another page. */
    data class Nav(
        override val id: String,
        override val title: UiText,
        val target: String,
        override val icon: String? = null,
        val description: ((RowContext) -> UiText?)? = null,
        override val badges: Set<Badge> = emptySet(),
        override val visible: Predicate = Always,
        override val enabled: Predicate = Always,
    ) : Row

    data class Toggle(
        val key: BoolKey,
        override val title: UiText,
        val description: ((RowContext) -> UiText?)? = null,
        override val icon: String? = key.icon,
        override val badges: Set<Badge> = key.badges,
        override val visible: Predicate = Always,
        override val enabled: Predicate = Always,
        /** Overrides the stored value (for settings whose unset default is computed). */
        val effectiveValue: ((RowContext) -> Boolean)? = null,
        override val id: String = key.name,
    ) : Row

    /** A slider over an [IntKey], [FloatKey] or [ChoiceKey] (letters). */
    data class Slider(
        val intKey: IntKey? = null,
        val floatKey: FloatKey? = null,
        val choiceKey: ChoiceKey? = null,
        override val title: UiText,
        val format: ValueFormat = ValueFormat.Plain,
        /** True when the fill starts at the middle of the range. */
        val centred: Boolean = false,
        /** Choice sliders: the first selectable index (Focused card size starts at XS = 1). */
        val choiceMin: Int = 0,
        val letters: List<String> = emptyList(),
        override val icon: String? = (intKey ?: floatKey ?: choiceKey)?.icon,
        override val badges: Set<Badge> = (intKey ?: floatKey ?: choiceKey)?.badges.orEmpty(),
        override val visible: Predicate = Always,
        override val enabled: Predicate = Always,
        override val id: String = (intKey ?: floatKey ?: choiceKey)!!.name,
    ) : Row

    /**
     * An inline dropdown. Either over a [ChoiceKey] (index) or a free [StringKey] with values;
     * [onSelect] replaces the default store-the-value behaviour when set.
     */
    data class Dropdown(
        override val id: String,
        override val title: UiText,
        val options: (RowContext) -> List<Option>,
        /** Index of the current option, or -1. */
        val selected: (RowContext) -> Int,
        val onSelect: (Int) -> Unit,
        /** Subtitle; by default the selected option's label. */
        val description: ((RowContext) -> UiText?)? = null,
        override val icon: String? = null,
        override val badges: Set<Badge> = emptySet(),
        override val visible: Predicate = Always,
        override val enabled: Predicate = Always,
    ) : Row

    /** A row that expands into plain action rows (Import, Export, Reset to default, Delete). */
    data class Expander(
        override val id: String,
        override val title: UiText,
        val children: List<Action>,
        val description: ((RowContext) -> UiText?)? = null,
        override val icon: String? = null,
        override val badges: Set<Badge> = emptySet(),
        override val visible: Predicate = Always,
        override val enabled: Predicate = Always,
        /** Index of the child focused when it opens (Cancel for destructive expanders). */
        val initialChild: Int = 0,
    ) : Row

    data class Color(
        val key: ColorKey,
        override val title: UiText,
        val description: ((RowContext) -> UiText?)? = null,
        /** Whether the picker offers *Prefer dynamic color*. */
        val allowDynamic: Boolean = true,
        override val icon: String? = key.icon,
        override val badges: Set<Badge> = key.badges,
        override val visible: Predicate = Always,
        override val enabled: Predicate = Always,
        override val id: String = key.name,
    ) : Row

    data class TextEntry(
        /** The stored setting, or null for a value kept elsewhere (then [value] and [onCommit]). */
        val key: StringKey?,
        override val title: UiText,
        /** Shown as the subtitle when the value is empty. */
        val emptyText: UiText? = null,
        override val icon: String? = key?.icon,
        override val badges: Set<Badge> = key?.badges.orEmpty(),
        override val visible: Predicate = Always,
        override val enabled: Predicate = Always,
        override val id: String = key!!.name,
        /** Called instead of storing when set (for example, to validate). */
        val onCommit: ((String) -> Unit)? = null,
        /** The current value when it is not a setting. */
        val value: ((RowContext) -> String)? = null,
    ) : Row

    /** Hours and minutes, stored as milliseconds. */
    data class Time(
        val key: LongKey,
        override val title: UiText,
        /** Time of day (12 h with AM/PM when the system is 12 h) rather than a duration. */
        val timeOfDay: Boolean,
        override val icon: String? = key.icon,
        override val badges: Set<Badge> = key.badges,
        override val visible: Predicate = Always,
        override val enabled: Predicate = Always,
        override val id: String = key.name,
    ) : Row

    /** A plain action row. */
    data class Action(
        override val id: String,
        override val title: UiText,
        val onClick: () -> Unit,
        val description: ((RowContext) -> UiText?)? = null,
        override val icon: String? = null,
        override val badges: Set<Badge> = emptySet(),
        override val visible: Predicate = Always,
        override val enabled: Predicate = Always,
        /** Draws a chevron as if it opened a page. */
        val chevron: Boolean = false,
    ) : Row

    /** A read-only label and value. */
    data class Info(
        override val id: String,
        override val title: UiText,
        val value: (RowContext) -> UiText?,
        override val icon: String? = null,
        override val badges: Set<Badge> = emptySet(),
        override val visible: Predicate = Always,
        override val enabled: Predicate = Always,
    ) : Row

    /** A block of free content at the end of a page (the clock format help). */
    data class Footer(
        override val id: String,
        val content: @Composable () -> Unit,
        override val title: UiText = raw(""),
        override val icon: String? = null,
        override val badges: Set<Badge> = emptySet(),
        override val visible: Predicate = Always,
        override val enabled: Predicate = Always,
    ) : Row
}

/** One guided settings page. */
@Immutable
data class Page(
    val id: String,
    val breadcrumb: UiText,
    val title: UiText,
    val description: UiText? = null,
    /** The icon in the left pane's accent tile. */
    val icon: String = "settings",
    val leftPane: LeftPane = LeftPane.Info,
    val rows: (RowContext) -> List<Row>,
    /** Extra description lines with badge icons (the badge legend on the root page). */
    val legend: Boolean = false,
)
