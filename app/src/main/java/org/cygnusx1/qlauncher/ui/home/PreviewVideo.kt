/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.home

import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

/**
 * A program card's preview video: after the card has kept focus for
 * 1000 ms, a muted player starts on a surface inside the card and fades in once it has a frame.
 */
@OptIn(UnstableApi::class)
@Composable
fun PreviewVideo(uri: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var started by remember(uri) { mutableStateOf(false) }
    var rendering by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) {
        delay(1000)
        started = true
    }
    if (!started) return
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(uri))
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    rendering = true
                }
            })
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    val alpha by animateFloatAsState(if (rendering) 1f else 0f, tween(200), label = "previewAlpha")
    AndroidView(
        factory = { ctx -> SurfaceView(ctx).also { player.setVideoSurfaceView(it) } },
        modifier = modifier.graphicsLayer { this.alpha = alpha },
    )
}
