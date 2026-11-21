/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.firstrun

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.ui.theme.MaterialIcon
import org.cygnusx1.qlauncher.ui.theme.QTheme

/** Steps of the first run. */
enum class FirstRunStep { Welcome, Features, Accessibility, Permissions, Notifications, Reports, Launcher, Start }

/** What the first run asks its host to do. */
interface FirstRunActions {
    fun openAccessibilitySettings()
    fun requestTvListings(onDone: () -> Unit)
    fun openNotificationAccess()
    fun openDefaultLauncher()
    fun setCrashErrorReports(enabled: Boolean)
    fun completeOnboarding()
}

/**
 * Full-screen pages with a large title, a paragraph and a page indicator; OK advances. The
 * reporting question is left out (there is no reporting backend), the accessibility page can open
 * the settings, and a default-launcher page explains the override.
 */
@Composable
fun FirstRun(actions: FirstRunActions) {
    var index by remember { mutableIntStateOf(0) }
    /** 0 = primary button, 1 = secondary. */
    var button by remember { mutableIntStateOf(0) }
    val steps = FirstRunStep.entries
    val step = steps[index]
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }

    fun next() {
        button = 0
        if (index < steps.lastIndex) index++ else actions.completeOnboarding()
    }

    fun primary() {
        when (step) {
            FirstRunStep.Accessibility -> if (button == 1) actions.openAccessibilitySettings() else next()
            FirstRunStep.Permissions -> actions.requestTvListings { next() }
            FirstRunStep.Notifications -> {
                if (button == 0) actions.openNotificationAccess()
                next()
            }
            FirstRunStep.Reports -> {
                actions.setCrashErrorReports(button == 0)
                next()
            }
            FirstRunStep.Launcher -> if (button == 1) actions.openDefaultLauncher() else next()
            FirstRunStep.Start -> actions.completeOnboarding()
            else -> next()
        }
    }

    val hasSecond = step in setOf(FirstRunStep.Accessibility, FirstRunStep.Notifications, FirstRunStep.Reports, FirstRunStep.Launcher)
    val accent = QTheme.colors.accent
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101014))
            .background(Brush.linearGradient(listOf(Color.Transparent, accent.copy(alpha = 0.35f), Color.Transparent)))
            .focusRequester(fr)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) {
                    if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)) primary()
                    return@onKeyEvent e.key != Key.Back
                }
                when (e.key) {
                    Key.DirectionLeft -> if (hasSecond) button = 0
                    Key.DirectionRight -> if (hasSecond) button = 1
                    Key.Back -> if (index > 0) { index--; button = 0 } else return@onKeyEvent false
                }
                true
            }
            .focusable()
            .testTag("first_run"),
    ) {
        AnimatedContent(step, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "firstRun") { s ->
            // Fixed anchors, so the icon, title, body and buttons land on the same pixels on
            // every page whatever the length of the text.
            Box(Modifier.fillMaxSize().padding(start = PAGE_START)) {
                Column(Modifier.padding(top = PAGE_TOP)) {
                    MaterialIcon(icon(s), size = 56.dp, tint = accent)
                    Spacer(Modifier.height(24.dp))
                    Text(stringResource(title(s)), color = Color.White, style = QTheme.type.onboardingTitle, maxLines = 1, modifier = Modifier.testTag("first_run_title"))
                    Spacer(Modifier.height(20.dp))
                    Text(stringResource(body(s)), color = Color.White.copy(alpha = 0.85f), style = QTheme.type.onboarding, modifier = Modifier.width(BODY_WIDTH).testTag("first_run_body"))
                }
                Row(Modifier.padding(top = BUTTONS_TOP).testTag("first_run_buttons")) {
                    val labels = when (s) {
                        FirstRunStep.Accessibility -> listOf(R.string.firstrun_next, R.string.firstrun_a11y_open)
                        FirstRunStep.Notifications, FirstRunStep.Reports -> listOf(R.string.firstrun_yes, R.string.firstrun_no)
                        FirstRunStep.Launcher -> listOf(R.string.firstrun_next, R.string.firstrun_launcher_open)
                        FirstRunStep.Start -> listOf(R.string.firstrun_get_started)
                        else -> listOf(R.string.firstrun_next)
                    }
                    labels.forEachIndexed { i, l ->
                        // The notification and reporting questions focus No by default (LaunchedEffect below).
                        val focused = button == i
                        Box(
                            Modifier
                                .padding(end = 16.dp)
                                .background(if (focused) Color.White else Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                                .padding(horizontal = 24.dp, vertical = 10.dp)
                                .testTag("first_run_button_$i"),
                        ) {
                            Text(stringResource(l), color = if (focused) Color.Black else Color.White, style = QTheme.type.panelRow)
                        }
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp).testTag("first_run_dots")) {
            steps.forEachIndexed { i, _ ->
                // Every dot has the same slot, so the larger current dot never moves the others.
                Box(Modifier.padding(horizontal = 3.dp).size(10.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(if (i == index) 10.dp else 8.dp)
                            .background(if (i == index) accent else Color.White.copy(alpha = 0.3f), CircleShape),
                    )
                }
            }
        }
    }
    // The notification and reporting questions focus No.
    LaunchedEffect(step) { if (step == FirstRunStep.Notifications || step == FirstRunStep.Reports) button = 1 }
}

private val PAGE_START = 160.dp
private val PAGE_TOP = 96.dp
private val BODY_WIDTH = 560.dp

/** Below the longest body (five lines at 1080p) on every page. */
private val BUTTONS_TOP = 418.dp

private fun icon(s: FirstRunStep) = when (s) {
    FirstRunStep.Welcome -> "waving_hand"
    FirstRunStep.Features -> "bolt"
    FirstRunStep.Accessibility -> "accessibility_new"
    FirstRunStep.Permissions -> "verified_user"
    FirstRunStep.Notifications -> "notifications"
    FirstRunStep.Reports -> "bug_report"
    FirstRunStep.Launcher -> "home"
    FirstRunStep.Start -> "rocket_launch"
}

private fun title(s: FirstRunStep) = when (s) {
    FirstRunStep.Welcome -> R.string.firstrun_welcome_title
    FirstRunStep.Features -> R.string.firstrun_features_title
    FirstRunStep.Accessibility -> R.string.firstrun_a11y_title
    FirstRunStep.Permissions -> R.string.firstrun_permissions_title
    FirstRunStep.Notifications -> R.string.firstrun_notifications_dialog
    FirstRunStep.Reports -> R.string.firstrun_reports_title
    FirstRunStep.Launcher -> R.string.firstrun_launcher_title
    FirstRunStep.Start -> R.string.firstrun_start_title
}

private fun body(s: FirstRunStep) = when (s) {
    FirstRunStep.Welcome -> R.string.firstrun_welcome_text
    FirstRunStep.Features -> R.string.firstrun_features_text
    FirstRunStep.Accessibility -> R.string.firstrun_a11y_text
    FirstRunStep.Permissions ->
        if (Build.VERSION.SDK_INT >= 26) R.string.firstrun_permissions_text else R.string.firstrun_permissions_text_no_channels
    FirstRunStep.Notifications -> R.string.firstrun_notifications_text
    FirstRunStep.Reports -> R.string.firstrun_reports_text
    FirstRunStep.Launcher -> R.string.firstrun_launcher_text
    FirstRunStep.Start -> R.string.firstrun_start_text
}
