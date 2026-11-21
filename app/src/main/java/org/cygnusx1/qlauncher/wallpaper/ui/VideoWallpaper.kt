/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import org.cygnusx1.qlauncher.container
import org.cygnusx1.qlauncher.wallpaper.WallpaperModule
import org.cygnusx1.qlauncher.wallpaper.engine.Acceptance
import org.cygnusx1.qlauncher.wallpaper.engine.DisplayMode
import org.cygnusx1.qlauncher.wallpaper.engine.DisplayedWallpaper
import org.cygnusx1.qlauncher.wallpaper.engine.SeedColors
import org.cygnusx1.qlauncher.wallpaper.engine.WallpaperContent
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How a video is decoded and shown, decided once per player. */
private data class PlayerSpec(
    val uri: String,
    /** SurfaceView with tunnelling; TextureView (which can be filtered and blurred) otherwise. */
    val surface: Boolean,
    val tunneling: Boolean,
    val decoderFallback: Boolean,
    val audioOff: Boolean,
)

/**
 * A video wallpaper with Media3 ExoPlayer:
 * - a SurfaceView with tunnelling when *Use hardware rendering* is on and nothing adjusts the
 *   picture, a TextureView otherwise (its layer paint carries the colour matrix);
 * - `rtsp://` and `rtspt://` force RTP over TCP, `rtspu://` uses UDP;
 * - volume 0 disables the audio track; *Enable decoders fallback* is passed on;
 * - it loops unless *Ignore update frequency* is on and the frequency is not 0; then its end
 *   brings the next wallpaper;
 * - the player is released while the launcher is stopped and resumes where it was (videos of at
 *   least 30 s with more than 10 s left);
 * - every *Dynamic color update frequency* seconds a 64 × 36 frame is sampled for the palette.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun VideoWallpaper(
    d: DisplayedWallpaper,
    c: WallpaperContent.Video,
    mode: DisplayMode,
    look: WallpaperLook,
    playing: Boolean,
    module: WallpaperModule,
) {
    val context = LocalContext.current
    val video = look.video
    val needsTexture = look.adjusted || mode == DisplayMode.BLUR || mode == DisplayMode.FIT_CENTER_BLURRED
    val spec = PlayerSpec(
        uri = c.uri,
        surface = video.tunneling && !needsTexture,
        tunneling = video.tunneling && !needsTexture,
        decoderFallback = video.decoderFallback,
        audioOff = video.volume <= 0f,
    )
    var videoSize by remember(c.uri) { mutableStateOf(IntSize(c.width, c.height)) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember(c.uri) { mutableStateOf<Bitmap?>(null) }
    var view by remember { mutableStateOf<View?>(null) }
    val playingNow by rememberUpdatedState(playing)
    val loop by rememberUpdatedState(video.loop)

    val player: ExoPlayer? = remember(spec, playing) {
        if (!playing) null else buildPlayer(context, spec, context.container.okHttp)
    }

    DisposableEffect(player) {
        val p = player ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && !loop) module.controller.onVideoEnded(d.wallpaper)
            }

            override fun onPlayerError(error: PlaybackException) {
                module.controller.reportFailure(d.wallpaper)
            }

            override fun onRenderedFirstFrame() {
                module.controller.reportPlaying(d.wallpaper)
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    videoSize = IntSize((size.width * size.pixelWidthHeightRatio).roundToInt(), size.height)
                }
            }
        }
        p.addListener(listener)
        module.videoPositions.take(c.uri)?.let { p.seekTo(it) }
        p.playWhenReady = true
        onDispose {
            p.removeListener(listener)
            // Leaving the launcher keeps the position; a wallpaper change does not.
            if (!playingNow) module.videoPositions.leave(c.uri, p.currentPosition, p.duration.takeIf { it != C.TIME_UNSET } ?: 0L)
            p.release()
        }
    }

    LaunchedEffect(player, video.volume, video.loop) {
        player?.volume = video.volume
        player?.repeatMode = if (video.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    // Frames for the palette and for blurred backgrounds.
    val needsFrames = mode == DisplayMode.FIT_CENTER_BLURRED || (mode == DisplayMode.BLUR && Build.VERSION.SDK_INT < 31)
    LaunchedEffect(player, view, video.colorFrequencySeconds, needsFrames) {
        val v = view ?: return@LaunchedEffect
        if (player == null) return@LaunchedEffect
        val colorEvery = (video.colorFrequencySeconds * 1000).toLong()
        if (colorEvery <= 0L && !needsFrames) return@LaunchedEffect
        val every = when {
            colorEvery <= 0L -> BACKGROUND_FRAME_MS
            needsFrames -> min(colorEvery, BACKGROUND_FRAME_MS)
            else -> colorEvery
        }
        delay(FIRST_FRAME_DELAY_MS)
        while (true) {
            val sample = grab(v, if (needsFrames) FRAME_WIDTH else SeedColors.SAMPLE_WIDTH, if (needsFrames) FRAME_HEIGHT else SeedColors.SAMPLE_HEIGHT)
            if (sample != null) {
                if (colorEvery > 0L) SeedColors.extract(sample)?.let(module::offerSeed)
                if (needsFrames) frame = sample
            }
            delay(every)
        }
    }

    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { boxSize = it }, contentAlignment = Alignment.Center) {
        val bg = frame
        if (bg != null && (mode == DisplayMode.FIT_CENTER_BLURRED || mode == DisplayMode.BLUR)) {
            StillWallpaper(bg, DisplayMode.BLUR, look)
        }
        val hideVideo = mode == DisplayMode.BLUR && Build.VERSION.SDK_INT < 31 && bg != null
        if (player != null && boxSize.width > 0 && !hideVideo) {
            val (w, h) = videoBox(videoSize, boxSize, mode)
            val density = LocalDensity.current
            val matrix = look.matrix
            // SurfaceView and TextureView cannot replace each other in place.
            key(spec.surface) {
                AndroidView(
                    factory = { ctx ->
                        val v: View = if (spec.surface) SurfaceView(ctx) else TextureView(ctx)
                        view = v
                        v
                    },
                    update = { v ->
                        when (v) {
                            is SurfaceView -> player.setVideoSurfaceView(v)
                            is TextureView -> {
                                player.setVideoTextureView(v)
                                v.setLayerPaint(matrix?.let { m -> Paint().apply { colorFilter = ColorMatrixColorFilter(m) } })
                            }
                        }
                    },
                    onRelease = { v ->
                        if (view === v) view = null
                    },
                    modifier = Modifier
                        .requiredSize(with(density) { w.toDp() }, with(density) { h.toDp() })
                        .liveBlur(if (mode == DisplayMode.BLUR) Acceptance.BLUR_MODE_BLUR else null)
                        .liveBlur(look.extraBlur),
                )
            }
        }
    }
}

/** The size of the video view for a display mode: cover, fill or contain. */
internal fun videoBox(video: IntSize, box: IntSize, mode: DisplayMode): Pair<Int, Int> {
    if (video.width <= 0 || video.height <= 0 || mode == DisplayMode.STRETCH) return box.width to box.height
    val sx = box.width.toFloat() / video.width
    val sy = box.height.toFloat() / video.height
    val s = when (mode) {
        DisplayMode.FIT_CENTER, DisplayMode.FIT_CENTER_BLURRED -> min(sx, sy)
        else -> max(sx, sy)
    }
    return (video.width * s).roundToInt().coerceAtLeast(1) to (video.height * s).roundToInt().coerceAtLeast(1)
}

@OptIn(UnstableApi::class)
private fun buildPlayer(context: Context, spec: PlayerSpec, client: OkHttpClient): ExoPlayer {
    val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(spec.decoderFallback)
    val selector = DefaultTrackSelector(context)
    selector.setParameters(
        selector.buildUponParameters()
            .setTunnelingEnabled(spec.tunneling)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, spec.audioOff),
    )
    val player = ExoPlayer.Builder(context, renderers).setTrackSelector(selector).build()
    player.setMediaSource(mediaSource(context, spec.uri, client))
    player.prepare()
    return player
}

@OptIn(UnstableApi::class)
private fun mediaSource(context: Context, uri: String, client: OkHttpClient): MediaSource {
    val parsed = Uri.parse(uri)
    return when (parsed.scheme?.lowercase()) {
        "rtsp", "rtspt", "rtspu" -> {
            val tcp = parsed.scheme?.lowercase() != "rtspu"
            val rtsp = parsed.buildUpon().scheme("rtsp").build()
            RtspMediaSource.Factory().setForceUseRtpTcp(tcp).createMediaSource(MediaItem.fromUri(rtsp))
        }
        else -> DefaultMediaSourceFactory(DefaultDataSource.Factory(context, OkHttpDataSource.Factory(client)))
            .createMediaSource(MediaItem.fromUri(parsed))
    }
}

/** A small copy of the frame on screen, or null when it cannot be read (tunnelled or protected video). */
private suspend fun grab(view: View, width: Int, height: Int): Bitmap? = when (view) {
    is TextureView -> runCatching { view.getBitmap(width, height) }.getOrNull()
    is SurfaceView -> {
        val holder = view.holder
        if (holder.surface?.isValid != true) {
            null
        } else {
            val bitmap = createBitmap(width, height)
            suspendCancellableCoroutine { cont ->
                runCatching {
                    PixelCopy.request(view, bitmap, { result ->
                        if (cont.isActive) cont.resume(if (result == PixelCopy.SUCCESS) bitmap else null)
                    }, Handler(Looper.getMainLooper()))
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
    }
    else -> null
}

private const val FRAME_WIDTH = 240
private const val FRAME_HEIGHT = 135
private const val BACKGROUND_FRAME_MS = 2_000L
private const val FIRST_FRAME_DELAY_MS = 500L
