/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.home

import android.util.Log
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.palette.graphics.Palette
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.toBitmap
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.Settings
import org.cygnusx1.qlauncher.ui.theme.DynamicPalette
import org.cygnusx1.qlauncher.ui.theme.MaterialIcon
import org.cygnusx1.qlauncher.ui.theme.QPalette
import org.cygnusx1.qlauncher.ui.theme.QTheme

/** Everything about how cards look, resolved once per settings change. */
@Immutable
data class CardStyle(
    val corner: Float,
    val background: Color,
    val iconTint: Color?,
    val zoom: Float,
    /** 0 None, 1 Drop shadow, 2 Glow. */
    val effect: Int,
    /** 0 None, 1 Pulse, 2 Breath, 3 Fade, 4 Shine. */
    val animation: Int,
    val glowSize: Dp,
    /** Null = the card's own colour. */
    val glowColor: Color?,
    /** 0 None, 1 Swap background/tint, 2 Custom. */
    val override: Int,
    val focusedBackground: Color,
    val focusedTint: Color?,
    val ripple: Boolean,
    val showProgress: Boolean,
    val showBadge: Boolean,
    val programZoom: Boolean,
    /** 0 Never, 1 Always, 2 When no background media is playing. */
    val playPreview: Int = 1,
) {
    companion object {
        fun from(s: Settings, palette: DynamicPalette, accent: Color): CardStyle {
            val bg = palette.resolve(s[Keys.Cards.background], QPalette.DefaultCardBackground)
            val tintStored = s[Keys.Cards.iconTint]
            val tint = if (tintStored == -1 || s[Keys.Cards.iconPack].isEmpty()) null else palette.resolve(tintStored, Color.White)
            val glowStored = s[Keys.Focus.glowColor]
            return CardStyle(
                corner = s[Keys.Cards.roundedCorners],
                background = bg,
                iconTint = tint,
                zoom = HomeGeometry.ZOOM.getOrElse(s[Keys.Focus.scale]) { 1.3f },
                effect = s[Keys.Focus.effect],
                animation = s[Keys.Focus.animation],
                glowSize = (s[Keys.Focus.glowSize] / 2f).dp,
                glowColor = if (glowStored == 0) null else palette.resolve(glowStored, accent),
                override = s[Keys.Focus.colorOverride],
                focusedBackground = palette.resolve(s[Keys.Focus.background], bg),
                focusedTint = s[Keys.Focus.iconTint].let { if (it == -1) null else palette.resolve(it, Color.White) },
                ripple = s[Keys.Cards.ripple],
                showProgress = s[Keys.Program.showProgress],
                showBadge = s[Keys.Program.showBadge],
                programZoom = s[Keys.Program.zoomOnFocus],
                playPreview = s[Keys.Program.playPreview],
            )
        }
    }
}

private fun Easing(interp: android.view.animation.Interpolator): Easing = Easing { interp.getInterpolation(it) }

/**
 * One card. The focused card is drawn scaled about its centre above its neighbours, which keep
 * their places; the glow is the card's elevation shadow drawn in a colour.
 */
@Composable
fun HomeCard(
    item: CardItem,
    width: Dp,
    height: Dp,
    focused: Boolean,
    style: CardStyle,
    pressed: Boolean,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    /** True while some app plays media in the background (for *Play preview video*). */
    mediaPlaying: Boolean = false,
) {
    val zoomTarget = if (focused && (item.kind != CardKind.Program || style.programZoom)) style.zoom else 1f
    val scale by animateFloatAsState(zoomTarget, tween(if (animate) 150 else 0), label = "zoom")
    var dominant by remember(item.key) { mutableStateOf<Color?>(null) }
    val baseBg = when {
        focused && style.override == 2 -> style.focusedBackground
        focused && style.override == 1 -> style.iconTint ?: dominant ?: style.background
        else -> style.background
    }
    val animatedBg by animateColorAsState(baseBg, tween(if (animate) 1000 else 0), label = "cardBg")
    // An icon set's transparent icon sits straight on the wallpaper, focused or not, from its
    // first frame: never faded out from the card colour a slot showed before.
    val bg = if (item.transparentImage) Color.Transparent else animatedBg
    val radius = height * style.corner / 2
    val shape = RoundedCornerShape(radius)

    // Glow / drop shadow.
    val glowBase = style.glowColor ?: dominant ?: QTheme.colors.accent
    val transition = rememberInfiniteTransition(label = "glow")
    val pulse by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(
            tween(
                when (style.animation) {
                    2 -> 2000
                    else -> 1000
                },
                easing = when (style.animation) {
                    1 -> Easing(OvershootInterpolator(4f))
                    2 -> Easing(DecelerateInterpolator())
                    3 -> Easing(DecelerateInterpolator())
                    4 -> Easing(AccelerateInterpolator())
                    else -> Easing { it }
                },
            ),
            RepeatMode.Reverse,
        ),
        label = "glowPhase",
    )
    val animating = focused && style.effect == 2 && style.animation != 0 && animate
    val elevation: Dp = when {
        !focused || style.effect == 0 -> 0.dp
        style.effect == 1 -> 5.dp
        else -> {
            val base = maxOf(style.glowSize, 4.dp)
            if (animating && (style.animation == 1 || style.animation == 2)) base + 15.dp * pulse else base
        }
    }
    val shadowColor = when {
        style.effect == 1 -> Color.Black
        animating && style.animation == 3 -> lerp(glowBase, Color.Transparent, pulse)
        animating && style.animation == 4 -> lerp(glowBase, Color.White, pulse)
        else -> glowBase
    }
    // No glow behind an icon set's icon: with no card, the shadow of the card's box shows
    // through it. The zoom and the title still mark the focused card.
    val drawShadow = elevation > 0.dp && !item.transparentImage && !(style.effect == 1 && bg.alpha < 200f / 255f)

    Box(
        modifier
            .size(width, height)
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (drawShadow) Modifier.shadow(elevation, shape, clip = false, ambientColor = shadowColor, spotColor = shadowColor) else Modifier)
            .clip(shape)
            .background(bg)
            .testTag("card_" + item.key),
    ) {
        CardContent(item, focused, style) { c -> if (dominant == null) dominant = c }
        val preview = item.program?.previewVideoUri
        if (focused && animate && preview != null && (style.playPreview == 1 || (style.playPreview == 2 && !mediaPlaying))) {
            PreviewVideo(preview, Modifier.fillMaxSize())
        }
        if (item.kind == CardKind.Program) ProgramExtras(item, style, width, radius)
        if (pressed && style.ripple) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.25f)))
    }
}

@Composable
private fun CardContent(item: CardItem, focused: Boolean, style: CardStyle, onDominant: (Color) -> Unit) {
    val tint = if (focused && style.override == 2) style.focusedTint else style.iconTint
    // A program whose poster cannot be loaded tries its thumbnail, then shows its title.
    val fallbacks = remember(item.image, item.program) {
        listOfNotNull(item.image, item.program?.thumbnailUri).distinct()
    }
    var attempt by remember(fallbacks) { mutableIntStateOf(0) }
    val model = fallbacks.getOrNull(attempt)
    when {
        model != null -> {
            // Banners, posters and artwork fill the card. The user's own picture is shown whole
            // (an icon that is not 16:9 keeps its edges) unless *Crop to fill* is on; on a TV
            // card it still spans the card, so a 16:9 one looks the same either way.
            val fill = if (item.customImage) item.cropToFill else item.kind == CardKind.TvApp || item.kind == CardKind.Program || item.kind == CardKind.NowPlaying || item.cropToFill
            val inset = !fill && item.kind != CardKind.TvApp
            AsyncImage(
                model = model,
                contentDescription = item.title,
                contentScale = if (fill) ContentScale.Crop else ContentScale.Fit,
                colorFilter = tint?.let { androidx.compose.ui.graphics.ColorFilter.tint(it) },
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (inset) Modifier.padding(12.dp) else Modifier),
                onState = { s ->
                    if (s is AsyncImagePainter.State.Error) {
                        Log.w(TAG, "Cannot load the image of ${item.key}: $model", s.result.throwable)
                        attempt++
                    }
                    if (s is AsyncImagePainter.State.Success) {
                        runCatching {
                            val bmp = s.result.image.toBitmap(64, 36)
                            Palette.from(bmp).maximumColorCount(8).generate { p ->
                                p?.dominantSwatch?.rgb?.let { onDominant(Color(it)) }
                            }
                        }
                    }
                },
            )
        }
        item.icon != null -> Column(
            Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            MaterialIcon(item.icon, size = 28.dp, tint = tint ?: Color.White)
            if (item.title.isNotEmpty() && item.kind != CardKind.Placeholder) {
                Text(
                    item.title,
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        else -> Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.CenterStart) {
            // Start-aligned: the platform centres an ellipsised line by its width before the
            // ellipsis, which pushes it past the card's left edge.
            Text(item.title, color = Color.White, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.ProgramExtras(item: CardItem, style: CardStyle, width: Dp, radius: Dp) {
    val p = item.program ?: return
    if (style.showBadge && p.logoUri != null) {
        AsyncImage(
            model = p.logoUri,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(5.dp)
                .size(width / 3, width / 3 * 9 / 16),
        )
    }
    val progress = item.progress
    if (style.showProgress && progress != null) {
        val trackW = width - radius * 2
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 5.dp)
                .size(trackW, 3.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.3f)),
        ) {
            Box(Modifier.fillMaxWidth(progress).height(3.dp).background(QTheme.colors.accent))
        }
    }
}

/** ARGB of a colour, for passing to non-Compose code. */
fun Color.argb(): Int = toArgb()

private const val TAG = "QLCard"
