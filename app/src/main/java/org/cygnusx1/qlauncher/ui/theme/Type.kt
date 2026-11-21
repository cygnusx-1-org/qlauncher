/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Text styles with the sizes Projectivy uses. Line heights are explicit so
 * Android 11 and 14 lay text out in the same boxes.
 */
class QTypography(family: FontFamily, private val scale: Float = 1f, bold: Boolean = false, italic: Boolean = false) {
    private val base = TextStyle(
        fontFamily = family,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

    private fun s(size: Float, line: Float, scaleX: Float = 1f, weight: FontWeight? = null): TextStyle = base.copy(
        fontSize = (size * scale).sp,
        lineHeight = (line * scale).sp,
        textGeometricTransform = if (scaleX != 1f) TextGeometricTransform(scaleX = scaleX) else null,
        fontWeight = weight ?: base.fontWeight,
    )

    /** Settings page title (accent colour). */
    val pageTitle = s(19f, 23.5f)

    /** Settings breadcrumb; home category title. */
    val breadcrumb = s(16f, 20f)
    val rowHeader = s(16f, 24f)

    /** Page description (grey). */
    val pageDescription = s(16f, 21.5f)

    /** Settings row title and dropdown option: 14 sp at 0.9 horizontal scale. */
    val rowTitle = s(14f, 18f, scaleX = 0.9f)

    /** Settings row description. */
    val rowDescription = s(12f, 15.5f)

    /** Side panel row title. */
    val panelRow = s(14f, 18f)
    val panelSection = s(12f, 15.5f)
    val panelTitle = s(19f, 24f)

    /** Home card title. */
    val cardTitle = s(19f, 23.5f)
    val programTitle = s(19f, 23.5f, weight = FontWeight.Light)
    val programDescription = s(14f, 18f)

    /** Left-header label. */
    val headerLabel = s(14f, 18f)
    val notificationCount = s(13f, 16f, weight = FontWeight.Bold)
    val onboarding = s(17f, 25f, scaleX = 0.9f)
    val onboardingTitle = s(34f, 42f)
    val badge = s(10f, 12f)

    fun clock(size: TextUnit) = base.copy(fontSize = size, lineHeight = size)
}
