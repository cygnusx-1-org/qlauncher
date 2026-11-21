/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.tv.material3.Text
import org.cygnusx1.qlauncher.settings.Badge
import org.cygnusx1.qlauncher.ui.theme.MaterialIcon
import org.cygnusx1.qlauncher.ui.theme.QPalette
import org.cygnusx1.qlauncher.ui.theme.QTheme

/** Text colours of a settings row (90 / 50 / 25 % opacity). */
object RowColors {
    fun title(focused: Boolean, enabled: Boolean) = when {
        !enabled -> Color.White.copy(alpha = 0.25f)
        focused -> Color.White
        else -> Color.White.copy(alpha = 0.9f)
    }

    fun description(focused: Boolean, enabled: Boolean) = when {
        !enabled -> Color.White.copy(alpha = 0.25f)
        focused -> Color.White.copy(alpha = 0.7f)
        else -> Color.White.copy(alpha = 0.5f)
    }
}

/** The row box with its focus pill (radius = half the row height). */
@Composable
fun GuidedRowFrame(
    focused: Boolean,
    minHeight: Dp,
    modifier: Modifier = Modifier,
    tag: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .background(if (focused) QPalette.GuidedFocusPill else Color.Transparent, RoundedCornerShape(50)),
        content = content,
    )
}

/** The 32 dp icon circle at the row start. */
@Composable
fun BoxScope.RowIcon(name: String?, enabled: Boolean, swatch: Color? = null) {
    val base = Modifier
        .align(Alignment.CenterStart)
        .padding(start = GuidedDimens.IconStart)
        .size(GuidedDimens.IconCircle)
    when {
        swatch != null -> Box(
            base
                .background(swatch, CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape),
        )
        name != null -> Box(base.background(QPalette.IconCircle, CircleShape), contentAlignment = Alignment.Center) {
            MaterialIcon(name, size = GuidedDimens.IconGlyph, tint = Color.White.copy(alpha = if (enabled) 0.5f else 0.2f))
        }
        else -> Unit
    }
}

/** Title (with inline badges) and an optional description. */
@Composable
fun RowTexts(
    title: String,
    badges: Set<Badge>,
    description: String?,
    focused: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    descriptionColor: Color? = null,
    descriptionContent: (@Composable () -> Unit)? = null,
) {
    Column(modifier) {
        val titleColor = RowColors.title(focused, enabled)
        val annotated = buildAnnotatedString {
            append(title)
            badges.sortedBy { it.ordinal }.forEach { b ->
                append(" ")
                appendInlineContent(b.name, " ")
            }
        }
        val inline = badges.associate { b ->
            b.name to InlineTextContent(Placeholder(1.1.em, 1.1.em, PlaceholderVerticalAlign.TextCenter)) {
                val (icon, tint) = when (b) {
                    Badge.AccessibilityRequired -> "accessibility_new" to titleColor
                    Badge.AccessibilityRecommended -> "accessibility" to titleColor.copy(alpha = titleColor.alpha * 0.6f)
                    Badge.Experimental -> "science" to QPalette.ExperimentalBadge
                }
                MaterialIcon(icon, size = 16.dp, tint = tint)
            }
        }
        Text(annotated, inlineContent = inline, color = titleColor, style = QTheme.type.rowTitle)
        if (descriptionContent != null) {
            Spacer(Modifier.height(GuidedDimens.TitleToDescription))
            descriptionContent()
        } else if (!description.isNullOrEmpty()) {
            Spacer(Modifier.height(GuidedDimens.TitleToDescription))
            Text(
                description,
                color = descriptionColor ?: RowColors.description(focused, enabled),
                style = QTheme.type.rowDescription,
            )
        }
    }
}

/** Material 3 switch in the accent colours, 52x32 dp, drawn only. */
@Composable
fun SettingsSwitch(checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    val accent = QTheme.colors.accent
    val container = QTheme.colors.accentContainer
    Switch(
        checked = checked,
        onCheckedChange = null,
        enabled = true,
        modifier = modifier.alpha(if (enabled) 1f else 0.4f),
        thumbContent = if (checked) {
            { MaterialIcon("check", size = 16.dp, tint = container) }
        } else {
            null
        },
        colors = SwitchDefaults.colors(
            checkedThumbColor = accent,
            checkedTrackColor = container,
            checkedBorderColor = container,
            checkedIconColor = container,
            uncheckedThumbColor = QPalette.SwitchOffThumb,
            uncheckedTrackColor = QPalette.SwitchOffTrack,
            uncheckedBorderColor = QPalette.SwitchOffOutline,
        ),
    )
}

/** Trailing marks. */
enum class Trailing { None, Chevron, DropDown, DropUp }

@Composable
fun BoxScope.RowTrailing(trailing: Trailing, enabled: Boolean) {
    val name = when (trailing) {
        Trailing.None -> return
        Trailing.Chevron -> "chevron_right"
        Trailing.DropDown -> "arrow_drop_down"
        Trailing.DropUp -> "arrow_drop_up"
    }
    MaterialIcon(
        name,
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .offset(x = -(GuidedDimens.ChevronCenterFromEnd - GuidedDimens.ChevronSize / 2)),
        size = GuidedDimens.ChevronSize,
        tint = Color.White.copy(alpha = if (enabled) 0.9f else 0.25f),
    )
}

/** The standard text row body: icon, texts, trailing mark. */
@Composable
fun StandardRow(
    title: String,
    badges: Set<Badge>,
    description: String?,
    icon: String?,
    focused: Boolean,
    enabled: Boolean,
    trailing: Trailing,
    tag: String,
    modifier: Modifier = Modifier,
    swatch: Color? = null,
    minHeight: Dp = GuidedDimens.RowMinHeight,
    endInset: Dp = if (trailing == Trailing.None) 16.dp else GuidedDimens.TitleEndChevron,
    descriptionColor: Color? = null,
    descriptionContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable BoxScope.() -> Unit)? = null,
) {
    GuidedRowFrame(focused, minHeight, modifier, tag) {
        RowIcon(icon, enabled, swatch)
        RowTexts(
            title,
            badges,
            description,
            focused,
            enabled,
            Modifier
                .align(Alignment.CenterStart)
                .padding(
                    start = GuidedDimens.TitleStart,
                    end = endInset,
                    top = GuidedDimens.RowPadTop,
                    bottom = GuidedDimens.RowPadBottom,
                ),
            descriptionColor,
            descriptionContent,
        )
        RowTrailing(trailing, enabled)
        trailingContent?.invoke(this)
    }
}

/** The badge legend under the root page description. */
@Composable
fun BadgeLegend() {
    val grey = Color.White.copy(alpha = 0.5f)
    Column {
        listOf(
            Triple("accessibility_new", grey, org.cygnusx1.qlauncher.R.string.settings_legend_a11y_required),
            Triple("accessibility", grey.copy(alpha = 0.3f), org.cygnusx1.qlauncher.R.string.settings_legend_a11y_recommended),
            Triple("science", QPalette.ExperimentalBadge, org.cygnusx1.qlauncher.R.string.settings_legend_experimental),
        ).forEach { (icon, tint, label) ->
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                MaterialIcon(icon, size = 18.dp, tint = tint)
                Spacer(Modifier.size(8.dp))
                Text(androidx.compose.ui.res.stringResource(label), color = grey, style = QTheme.type.pageDescription)
            }
        }
    }
}
