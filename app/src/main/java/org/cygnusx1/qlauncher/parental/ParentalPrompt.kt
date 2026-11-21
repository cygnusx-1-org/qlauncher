/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.parental

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.container
import org.cygnusx1.qlauncher.ui.theme.MaterialIcon
import org.cygnusx1.qlauncher.ui.theme.QPalette
import org.cygnusx1.qlauncher.ui.theme.QTheme

/** What the parental prompt is for. */
enum class ParentalPromptMode {
    /** Choose a new code: enter it, OK, enter it again, OK. Shows the recovery code the first time. */
    SetCode,

    /** Ask for the code ("Restricted access"); succeeds the moment the right keys are entered. */
    Verify,
}

private sealed interface PromptStep {
    data object Enter : PromptStep
    data class Confirm(val first: List<ParentalToken>) : PromptStep
    data class Recovery(val code: String) : PromptStep
    data object Verify : PromptStep
}

/**
 * The full-screen parental lock page, drawn over the screen behind it dimmed with
 * [QPalette.ParentalOverlay]. It takes focus and reads D-pad arrows and digit keys itself, with
 * no per-key feedback. BACK cancels (Projectivy ignores BACK here; QLauncher lets it out).
 *
 * In [ParentalPromptMode.Verify] mode [onSuccess] runs as soon as the entered keys end with the
 * code (or the recovery code); the validation is recorded in [manager] first, so the caller's
 * next `needsCodeToLaunch` check passes. In [ParentalPromptMode.SetCode] mode [onSuccess] runs
 * once the code is stored (after the recovery code has been shown, the first time).
 *
 * The padlock and the first text line sit at the same place in every step.
 */
@Composable
fun ParentalPrompt(
    mode: ParentalPromptMode,
    onSuccess: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    manager: ParentalManager = LocalContext.current.container.system.parental,
) {
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val matcher = remember(mode, manager) { if (mode == ParentalPromptMode.Verify) manager.newMatcher() else null }
    var step by remember(mode) { mutableStateOf(if (mode == ParentalPromptMode.Verify) PromptStep.Verify else PromptStep.Enter) }
    val entered = remember(mode) { mutableListOf<ParentalToken>() }
    var error by remember { mutableStateOf<Int?>(null) }
    var errorSerial by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var okDown by remember { mutableStateOf(false) }
    var backDown by remember { mutableStateOf(false) }

    fun finish(success: Boolean) {
        if (finished) return
        finished = true
        if (success) onSuccess() else onCancel()
    }

    fun showError(res: Int) {
        error = res
        errorSerial++
    }

    // Nothing to ask: no code is set.
    LaunchedEffect(matcher, mode) {
        if (mode == ParentalPromptMode.Verify && matcher == null) finish(true)
    }
    LaunchedEffect(errorSerial) {
        if (error != null) {
            delay(ERROR_MILLIS)
            error = null
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun onToken(token: ParentalToken) {
        when (step) {
            PromptStep.Verify -> when (matcher?.onToken(token)) {
                CodeMatcher.Result.Matched, CodeMatcher.Result.RecoveryMatched -> {
                    manager.onValidated()
                    finish(true)
                }
                CodeMatcher.Result.Wrong -> showError(R.string.system_parental_wrong_code)
                else -> Unit
            }
            PromptStep.Enter, is PromptStep.Confirm -> if (entered.size < ParentalManager.MAX_CODE_LENGTH) entered += token
            is PromptStep.Recovery -> Unit
        }
    }

    fun onOk() {
        when (val s = step) {
            PromptStep.Enter -> if (entered.isNotEmpty()) {
                step = PromptStep.Confirm(entered.toList())
                entered.clear()
            }
            is PromptStep.Confirm -> {
                if (entered.isEmpty()) return
                if (entered != s.first) {
                    entered.clear()
                    step = PromptStep.Enter
                    showError(R.string.system_parental_codes_differ)
                    return
                }
                val code = entered.toList()
                entered.clear()
                busy = true
                scope.launch {
                    val recovery = manager.setCode(code)
                    busy = false
                    if (recovery != null) step = PromptStep.Recovery(recovery) else finish(true)
                }
            }
            is PromptStep.Recovery -> finish(true)
            PromptStep.Verify -> Unit
        }
    }

    fun onBack() {
        // The code is already stored once the recovery code shows.
        finish(step is PromptStep.Recovery)
    }

    if (LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler(enabled = !finished) { onBack() }
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(QPalette.ParentalOverlay)
            .onPreviewKeyEvent { ev ->
                val native = ev.nativeKeyEvent
                val code = native.keyCode
                if (code in PASS_THROUGH_KEYS) return@onPreviewKeyEvent false
                val down = ev.type == KeyEventType.KeyDown
                if (busy || finished) return@onPreviewKeyEvent true
                when {
                    code in OK_KEYS -> if (down) {
                        if (native.repeatCount == 0) okDown = true
                    } else if (okDown) {
                        okDown = false
                        onOk()
                    }
                    code in BACK_KEYS -> if (down) {
                        if (native.repeatCount == 0) backDown = true
                    } else if (backDown) {
                        backDown = false
                        onBack()
                    }
                    down && native.repeatCount == 0 -> ParentalToken.fromKeyCode(code)?.let(::onToken)
                }
                true
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        val centerY = maxHeight / 2
        val accent = QTheme.colors.accent
        MaterialIcon(
            name = "lock",
            size = LOCK_SIZE,
            tint = accent,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = centerY + LOCK_CENTER_OFFSET - LOCK_SIZE / 2),
        )
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 96.dp)
                .offset(y = centerY + TEXT_TOP_OFFSET),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val style = QTheme.type.cardTitle
            when (val s = step) {
                is PromptStep.Recovery -> {
                    Text(stringResource(R.string.system_parental_code_saved), style = style, color = QPalette.TextPrimary, textAlign = TextAlign.Center)
                    Text(
                        stringResource(R.string.system_parental_recovery_code, s.code),
                        style = style,
                        color = accent,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(R.string.system_parental_recovery_help),
                        style = QTheme.type.pageDescription,
                        color = QPalette.TextTitleUnfocused,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        stringResource(R.string.system_parental_press_ok),
                        style = QTheme.type.pageDescription,
                        color = QPalette.TextTitleUnfocused,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                else -> {
                    val message = when (s) {
                        PromptStep.Enter -> stringResource(R.string.system_parental_new_code)
                        is PromptStep.Confirm -> stringResource(R.string.system_parental_confirm_code)
                        else -> stringResource(R.string.system_parental_restricted)
                    }
                    // Two lines are always reserved so the error line below never moves.
                    Text(message, style = style, color = QPalette.TextPrimary, textAlign = TextAlign.Center, minLines = 2)
                    val err = error
                    Text(
                        text = if (err != null) stringResource(err) else " ",
                        style = style,
                        color = accent,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .alpha(if (err != null) 1f else 0f),
                    )
                }
            }
        }
    }
}

private const val ERROR_MILLIS = 2_500L
private val LOCK_SIZE = 112.dp

/** The padlock's centre sits this far from the screen's centre (Projectivy: about 19 dp above). */
private val LOCK_CENTER_OFFSET = (-19).dp

/** The first text line's top, from the screen's centre. */
private val TEXT_TOP_OFFSET = 58.dp

private val OK_KEYS = setOf(
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
    AndroidKeyEvent.KEYCODE_BUTTON_A,
)
private val BACK_KEYS = setOf(
    AndroidKeyEvent.KEYCODE_BACK,
    AndroidKeyEvent.KEYCODE_ESCAPE,
    AndroidKeyEvent.KEYCODE_BUTTON_B,
)

/** Keys the system keeps: volume, power and the like. */
private val PASS_THROUGH_KEYS = setOf(
    AndroidKeyEvent.KEYCODE_VOLUME_UP,
    AndroidKeyEvent.KEYCODE_VOLUME_DOWN,
    AndroidKeyEvent.KEYCODE_VOLUME_MUTE,
    AndroidKeyEvent.KEYCODE_MUTE,
    AndroidKeyEvent.KEYCODE_POWER,
    AndroidKeyEvent.KEYCODE_SLEEP,
    AndroidKeyEvent.KEYCODE_WAKEUP,
    AndroidKeyEvent.KEYCODE_HOME,
    AndroidKeyEvent.KEYCODE_TV_POWER,
)
