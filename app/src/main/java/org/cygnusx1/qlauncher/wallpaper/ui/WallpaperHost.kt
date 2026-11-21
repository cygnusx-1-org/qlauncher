/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cygnusx1.qlauncher.container
import org.cygnusx1.qlauncher.ui.theme.DynamicPalette
import org.cygnusx1.qlauncher.wallpaper.WallpaperModule
import org.cygnusx1.qlauncher.wallpaper.engine.Acceptance
import org.cygnusx1.qlauncher.wallpaper.engine.BlurSpec
import org.cygnusx1.qlauncher.wallpaper.engine.DisplayMode
import org.cygnusx1.qlauncher.wallpaper.engine.DisplayedWallpaper
import org.cygnusx1.qlauncher.wallpaper.engine.PictureAdjust
import org.cygnusx1.qlauncher.wallpaper.engine.ShadowOverlay
import org.cygnusx1.qlauncher.wallpaper.engine.StackBlur
import org.cygnusx1.qlauncher.wallpaper.engine.WallpaperContent
import org.cygnusx1.qlauncher.wallpaper.source.DynamicGradient
import kotlin.random.Random

/** The wallpaper transitions, in the order of the *Image transition* setting. */
enum class TransitionKind(val durationMs: Int) {
    NONE(200),
    RANDOM(800),
    FADE(800),
    ZOOM(800),
    HORIZONTAL_SLIDE(800),
    VERTICAL_SLIDE(800),
    ;

    companion object {
        private val ANIMATED = listOf(FADE, ZOOM, HORIZONTAL_SLIDE, VERTICAL_SLIDE)

        /** The setting's transition; *Random* picks one of the animated ones per change. */
        fun resolve(setting: Int, random: Random = Random.Default): TransitionKind {
            val kind = entries.getOrElse(setting) { FADE }
            return if (kind == RANDOM) ANIMATED[random.nextInt(ANIMATED.size)] else kind
        }
    }
}

@Stable
private class Layer(val displayed: DisplayedWallpaper, val kind: TransitionKind, val progress: Animatable<Float, *>)

/**
 * Draws the current wallpaper full screen, behind everything: display mode, picture adjustment,
 * extra blur, shadow overlay and transitions, for pictures, animated pictures, Lottie, video,
 * colours and the *Dynamic colors* gradient. Every setting applies live.
 *
 * It follows its lifecycle: while stopped, animations and video are paused (video is released
 * and resumes at the same position) and the controller's timer stops. Several hosts may exist at
 * once (home screen, the *Picture adjustment* preview); each draws the same wallpaper.
 */
@Composable
fun WallpaperHost(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember(context) { context.container }
    val module = remember(container) { container.wallpaper }
    val controller = module.controller
    val displayed by controller.displayed.collectAsState()
    val settings by container.settings.settings.collectAsState()
    val palette by container.theme.palette.collectAsState()
    val look = remember(settings, palette) { WallpaperLook.from(settings, palette) }
    val playing = rememberStarted { started -> if (started) controller.onStart() else controller.onStop() }

    val layers = remember { mutableStateListOf<Layer>() }
    val lookState = rememberUpdatedState(look)
    LaunchedEffect(displayed?.sequence) {
        val d = displayed ?: return@LaunchedEffect
        module.videoPositions.keepOnly((d.content as? WallpaperContent.Video)?.uri)
        val previous = layers.lastOrNull()
        val involvesVideo = d.content is WallpaperContent.Video || previous?.displayed?.content is WallpaperContent.Video
        val kind = TransitionKind.resolve(lookState.value.transition)
        // Only the latest layer stays under the incoming one.
        while (layers.size > 1) layers.removeAt(0)
        previous?.progress?.snapTo(1f)
        if (previous == null || involvesVideo) {
            // The first wallpaper appears at once; two players never run together.
            layers.clear()
            layers.add(Layer(d, kind, Animatable(1f)))
            return@LaunchedEffect
        }
        val incoming = Layer(d, kind, Animatable(0f))
        layers.add(incoming)
        incoming.progress.animateTo(1f, tween(kind.durationMs, easing = if (kind == TransitionKind.NONE) LinearEasing else FastOutSlowInEasing))
        while (layers.size > 1) layers.removeAt(0)
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        for (layer in layers) {
            key(layer.displayed.sequence) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val p = layer.progress.value
                            alpha = p
                            when (layer.kind) {
                                TransitionKind.ZOOM -> {
                                    val s = 1.02f - 0.02f * p
                                    scaleX = s
                                    scaleY = s
                                }
                                TransitionKind.HORIZONTAL_SLIDE -> translationX = size.width * 0.02f * (1f - p)
                                TransitionKind.VERTICAL_SLIDE -> translationY = size.height * 0.02f * (1f - p)
                                else -> Unit
                            }
                        },
                ) {
                    WallpaperLayer(layer.displayed, look, playing, palette, module)
                }
            }
        }
        ShadowOverlayLayer(look)
    }
}

/** True between ON_START and ON_STOP of the composition's lifecycle; [onChange] mirrors it. */
@Composable
private fun rememberStarted(onChange: (Boolean) -> Unit): Boolean {
    val owner = LocalLifecycleOwner.current
    var started by remember { mutableStateOf(false) }
    val callback by rememberUpdatedState(onChange)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!started) {
                    started = true
                    callback(true)
                }
                Lifecycle.Event.ON_STOP -> if (started) {
                    started = false
                    callback(false)
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (started) {
                started = false
                callback(false)
            }
        }
    }
    return started
}

@Composable
private fun WallpaperLayer(d: DisplayedWallpaper, look: WallpaperLook, playing: Boolean, palette: DynamicPalette, module: WallpaperModule) {
    val c = d.content
    val mode = Acceptance.drawMode(c.width, c.height, look.displayMode, look.wrongFormatMode, look.minAcceptedWidth, d.wallpaper.displayMode)
    when (c) {
        is WallpaperContent.Still -> StillWallpaper(c.bitmap, mode, look)
        is WallpaperContent.Animated -> AnimatedWallpaper(c, mode, look, playing)
        is WallpaperContent.Lottie -> LottieWallpaper(c, mode, look, playing)
        is WallpaperContent.Video -> VideoWallpaper(d, c, mode, look, playing, module)
        is WallpaperContent.Solid -> SolidWallpaper(c.color, look)
        WallpaperContent.DynamicColors -> {
            val gradient by module.dynamicColors.gradient.collectAsState()
            DynamicGradientWallpaper(gradient, palette, look)
        }
    }
}

// ---- Pictures ----

/** Blurred, down-sampled bitmaps for one picture and view size. */
internal object Blurs {
    /** The centre crop of [src] scaled down by [spec] and blurred; drawn stretched over the view. */
    fun cover(src: Bitmap, viewWidth: Int, viewHeight: Int, spec: BlurSpec): Bitmap {
        val g = StillGeometry.coverCrop(src.width, src.height, viewWidth, viewHeight)
        return StackBlur.downSampled(src, Rect(g.left, g.top, g.right, g.bottom), viewWidth, viewHeight, spec.downSample, spec.radius)
    }

    /**
     * The picture as [mode] draws it, scaled down by the extra blur's down-sample and blurred
     * with its radius: the *Blur* setting applies after the display mode.
     */
    fun composite(src: Bitmap, mode: DisplayMode, viewWidth: Int, viewHeight: Int, extra: BlurSpec): Bitmap {
        val d = extra.downSample.coerceAtLeast(1)
        val w = (viewWidth / d).coerceAtLeast(1)
        val h = (viewHeight / d).coerceAtLeast(1)
        val out = createBitmap(w, h)
        val canvas = Canvas(out)
        canvas.drawColor(android.graphics.Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val geometry = StillGeometry.of(src.width, src.height, mode, viewWidth, viewHeight)
        val sx = w.toFloat() / viewWidth
        val sy = h.toFloat() / viewHeight
        val background = when (mode) {
            DisplayMode.FIT_CENTER_BLURRED -> cover(src, viewWidth, viewHeight, Acceptance.FIT_BACKGROUND_BLUR)
            DisplayMode.BLUR -> cover(src, viewWidth, viewHeight, Acceptance.BLUR_MODE_BLUR)
            else -> null
        }
        if (background != null) canvas.drawBitmap(background, null, Rect(0, 0, w, h), paint)
        if (mode != DisplayMode.BLUR) {
            val s = geometry.src
            val t = geometry.dst
            canvas.drawBitmap(
                src,
                Rect(s.left, s.top, s.right, s.bottom),
                Rect((t.left * sx).toInt(), (t.top * sy).toInt(), (t.right * sx).toInt(), (t.bottom * sy).toInt()),
                paint,
            )
        }
        StackBlur.blurBitmap(out, extra.radius)
        return out
    }
}

@Composable
internal fun StillWallpaper(bitmap: Bitmap, mode: DisplayMode, look: WallpaperLook) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val geometry = remember(bitmap, mode, size) { StillGeometry.of(bitmap.width, bitmap.height, mode, size.width, size.height) }
    val background: ImageBitmap? = remember(bitmap, mode, size) {
        if (size.width <= 0) return@remember null
        when (mode) {
            DisplayMode.FIT_CENTER_BLURRED -> Blurs.cover(bitmap, size.width, size.height, Acceptance.FIT_BACKGROUND_BLUR).asImageBitmap()
            DisplayMode.BLUR -> Blurs.cover(bitmap, size.width, size.height, Acceptance.BLUR_MODE_BLUR).asImageBitmap()
            else -> null
        }
    }
    val extra = look.extraBlur
    // Computed off the main thread; the previous result stays on screen until the new one is ready.
    val composite by produceState<ImageBitmap?>(null, bitmap, mode, size, extra) {
        if (extra == null || size.width <= 0) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.Default) { Blurs.composite(bitmap, mode, size.width, size.height, extra).asImageBitmap() }
    }
    val cf = look.colorFilter
    Canvas(Modifier.fillMaxSize().onSizeChanged { size = it }) {
        val full = IntSize(this.size.width.toInt(), this.size.height.toInt())
        val blurred = composite
        if (extra != null && blurred != null) {
            drawImage(blurred, dstSize = full, colorFilter = cf, filterQuality = FilterQuality.Low)
            return@Canvas
        }
        background?.let { drawImage(it, dstSize = full, colorFilter = cf, filterQuality = FilterQuality.Low) }
        if (mode != DisplayMode.BLUR || background == null) {
            val s = geometry.src
            val t = geometry.dst
            drawImage(
                image,
                srcOffset = IntOffset(s.left, s.top),
                srcSize = IntSize(s.width.coerceAtLeast(1), s.height.coerceAtLeast(1)),
                dstOffset = IntOffset(t.left, t.top),
                dstSize = IntSize(t.width, t.height),
                colorFilter = cf,
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

/** Applies [filter] to everything drawn inside, through one layer. */
private fun Modifier.colorFiltered(filter: ColorFilter?): Modifier = if (filter == null) {
    this
} else {
    drawWithContent {
        drawIntoCanvas { canvas ->
            val paint = androidx.compose.ui.graphics.Paint().apply { colorFilter = filter }
            canvas.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, size), paint)
            drawContent()
            canvas.restore()
        }
    }
}

/** A RenderEffect blur (API 31+) approximating a down-sample blur; a no-op below. */
@Composable
internal fun Modifier.liveBlur(spec: BlurSpec?): Modifier {
    if (spec == null || Build.VERSION.SDK_INT < 31) return this
    val radius = with(LocalDensity.current) { spec.effectiveRadiusPx.toDp() }
    return blur(radius, radius, BlurredEdgeTreatment.Rectangle)
}

private fun contentScaleOf(mode: DisplayMode) = when (mode) {
    DisplayMode.STRETCH -> androidx.compose.ui.layout.ContentScale.FillBounds
    DisplayMode.FIT_CENTER, DisplayMode.FIT_CENTER_BLURRED -> androidx.compose.ui.layout.ContentScale.Fit
    else -> androidx.compose.ui.layout.ContentScale.Crop
}

/**
 * Animated pictures and Lottie: the moving picture with the display mode as a content scale.
 * Blurred backgrounds come from the first frame. On API 28–30, where views cannot be blurred
 * live, the *Blur* mode and the extra blur show the blurred first frame instead.
 */
@Composable
private fun MovingWallpaper(firstFrame: Bitmap?, mode: DisplayMode, look: WallpaperLook, content: @Composable (Modifier) -> Unit) {
    val live = Build.VERSION.SDK_INT >= 31
    val staticBlur = !live && (mode == DisplayMode.BLUR || look.extraBlur != null)
    if (staticBlur && firstFrame != null) {
        StillWallpaper(firstFrame, mode, look)
        return
    }
    Box(Modifier.fillMaxSize().liveBlur(look.extraBlur)) {
        if (mode == DisplayMode.FIT_CENTER_BLURRED && firstFrame != null) StillWallpaper(firstFrame, DisplayMode.BLUR, look)
        val blur = if (mode == DisplayMode.BLUR) Acceptance.BLUR_MODE_BLUR else null
        content(Modifier.fillMaxSize().liveBlur(blur).colorFiltered(look.colorFilter))
    }
}

@Composable
private fun AnimatedWallpaper(c: WallpaperContent.Animated, mode: DisplayMode, look: WallpaperLook, playing: Boolean) {
    val painter = remember(c) { DrawablePainter(c.drawable) }
    DisposableEffect(painter, playing) {
        if (playing) painter.start() else painter.stop()
        onDispose { painter.stop() }
    }
    MovingWallpaper(c.firstFrame, mode, look) { m ->
        androidx.compose.foundation.Image(painter = painter, contentDescription = null, modifier = m, contentScale = contentScaleOf(mode))
    }
}

@Composable
private fun LottieWallpaper(c: WallpaperContent.Lottie, mode: DisplayMode, look: WallpaperLook, playing: Boolean) {
    val progress by animateLottieCompositionAsState(c.composition, iterations = LottieConstants.IterateForever, isPlaying = playing)
    MovingWallpaper(c.firstFrame, mode, look) { m ->
        LottieAnimation(composition = c.composition, progress = { progress }, modifier = m, contentScale = contentScaleOf(mode))
    }
}

@Composable
private fun SolidWallpaper(color: Int, look: WallpaperLook) {
    val shown = remember(color, look.matrix) { look.matrix?.let { PictureAdjust.apply(it, color) } ?: color }
    Box(Modifier.fillMaxSize().background(Color(shown)))
}

/**
 * The *Dynamic colors* wallpaper: a diagonal gradient that animates over 1000 ms, linearly, to
 * tone 600 of the focused card's light colour and tone 800 of its dark colour, or to the current
 * palette's tones until a card is focused.
 */
@Composable
private fun DynamicGradientWallpaper(gradient: DynamicGradient?, palette: DynamicPalette, look: WallpaperLook) {
    val light = gradient?.light ?: palette.shade(600) ?: 0xFF4A4A4A.toInt()
    val dark = gradient?.dark ?: palette.shade(800) ?: 0xFF202020.toInt()
    val shownLight = look.matrix?.let { PictureAdjust.apply(it, light) } ?: light
    val shownDark = look.matrix?.let { PictureAdjust.apply(it, dark) } ?: dark
    val a by animateColorAsState(Color(shownLight), tween(1000, easing = LinearEasing), label = "dynamicLight")
    val b by animateColorAsState(Color(shownDark), tween(1000, easing = LinearEasing), label = "dynamicDark")
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.linearGradient(listOf(a, b), start = Offset.Zero, end = Offset(size.width, size.height)))
    }
}

// ---- Shadow overlay ----

@Composable
private fun ShadowOverlayLayer(look: WallpaperLook) {
    val tint by animateColorAsState(look.shadowTint, tween(1000), label = "shadowTint")
    if (!ShadowOverlay.isVisible(look.shadowH, look.shadowV, look.shadowOpacity)) return
    val overlay = remember(look.shadowH, look.shadowV) { ShadowOverlay.render(look.shadowH, look.shadowV)?.asImageBitmap() } ?: return
    Canvas(Modifier.fillMaxSize()) {
        drawImage(
            overlay,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            alpha = look.shadowOpacity.coerceIn(0f, 1f),
            colorFilter = ColorFilter.tint(tint, BlendMode.SrcIn),
            filterQuality = FilterQuality.Low,
        )
    }
}
