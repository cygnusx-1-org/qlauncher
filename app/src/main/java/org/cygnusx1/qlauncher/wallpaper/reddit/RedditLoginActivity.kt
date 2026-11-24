/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.reddit

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.container
import org.cygnusx1.qlauncher.ui.LocaleHelper
import org.cygnusx1.qlauncher.ui.theme.MaterialIcon
import org.cygnusx1.qlauncher.ui.theme.QLauncherTheme
import org.cygnusx1.qlauncher.ui.theme.QPalette
import org.cygnusx1.qlauncher.ui.theme.QTheme
import kotlin.math.roundToInt

/**
 * The Reddit login: Reddit's authorize page in a full-screen WebView, the
 * `redreader://` redirect caught before it leaves the WebView, the state checked, the code
 * exchanged for tokens. Returns RESULT_OK once logged in. BACK goes back in the page history,
 * then cancels.
 */
class RedditLoginActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private sealed interface Phase {
        data object Browsing : Phase
        data object Exchanging : Phase
        data class Failed(val message: String, val retry: Boolean) : Phase
        data object NoWebView : Phase
    }

    private var phase by mutableStateOf<Phase>(Phase.Browsing)
    private var oauthState: String = ""
    private var webView: WebView? = null
    private var triedFallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        oauthState = savedInstanceState?.getString(KEY_STATE) ?: RedditOAuth.newState()
        if (!hasWebView()) phase = Phase.NoWebView
        val container = container
        setContent {
            val colors by container.theme.colors.collectAsState()
            QLauncherTheme(colors = colors) {
                LoginScreen()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_STATE, oauthState)
    }

    override fun onDestroy() {
        webView?.let {
            it.stopLoading()
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    // WebViewCompat would need androidx.webkit; the feature flag covers Android 7.
    @SuppressLint("WebViewApiAvailability")
    private fun hasWebView(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 26) {
            WebView.getCurrentWebViewPackage() != null
        } else {
            packageManager.hasSystemFeature(PackageManager.FEATURE_WEBVIEW)
        }
    }.getOrDefault(false)

    @Composable
    private fun LoginScreen() {
        BackHandler {
            val w = webView
            if (phase == Phase.Browsing && w != null && w.canGoBack()) w.goBack() else cancel()
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (val p = phase) {
                Phase.Browsing, Phase.Exchanging -> {
                    AndroidView(
                        factory = { ctx -> createWebView(ctx)?.let { ScaledHost(ctx, PAGE_SCALE).apply { addView(it) } } ?: View(ctx) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (p == Phase.Exchanging) {
                        Box(Modifier.fillMaxSize().background(QPalette.ParentalOverlay), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.wallpaper_reddit_login_signing_in), style = QTheme.type.pageTitle, color = QPalette.TextPrimary)
                        }
                    }
                }
                is Phase.Failed -> Message(p.message, p.retry)
                Phase.NoWebView -> Message(stringResource(R.string.wallpaper_reddit_no_webview), retry = false)
            }
        }
    }

    @Composable
    private fun Message(text: String, retry: Boolean) {
        val focus = remember { FocusRequester() }
        Column(
            Modifier.fillMaxSize().padding(48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MaterialIcon("account_circle", size = 48.dp, tint = QTheme.colors.accent)
            Text(
                stringResource(R.string.wallpaper_reddit_login_title),
                style = QTheme.type.pageTitle,
                color = QTheme.colors.accent,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text,
                style = QTheme.type.pageDescription,
                color = QPalette.TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 560.dp).padding(top = 12.dp, bottom = 24.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (retry) {
                    Button(onClick = ::restart, modifier = Modifier.focusRequester(focus)) {
                        Text(stringResource(R.string.wallpaper_reddit_retry))
                    }
                    Button(onClick = ::cancel) { Text(stringResource(R.string.wallpaper_reddit_close)) }
                } else {
                    Button(onClick = ::cancel, modifier = Modifier.focusRequester(focus)) {
                        Text(stringResource(R.string.wallpaper_reddit_close))
                    }
                }
            }
        }
        LaunchedEffect(text) { runCatching { focus.requestFocus() } }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView? {
        val w = try {
            TvWebView(context)
        } catch (e: Exception) {
            // Some TV builds ship without a WebView implementation.
            Log.w(TAG, "No WebView available", e)
            phase = Phase.NoWebView
            return null
        }
        // The first WebView in the process puts the application's resources back in the system language.
        LocaleHelper.reapply(applicationContext)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.isFocusable = true
        w.isFocusableInTouchMode = true
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (!RedditOAuth.isRedirect(url)) return false
                handleRedirect(url)
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (RedditOAuth.isRedirect(url)) {
                    view.stopLoading()
                    handleRedirect(url)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                val url = request.url.toString()
                if (RedditOAuth.isRedirect(url)) return
                Log.w(TAG, "Login page error ${error.errorCode} for ${RedditOAuth.redact(url)}")
                if (!triedFallback && url.startsWith(RedditOAuth.AUTHORIZE_URL)) {
                    triedFallback = true
                    load(view, RedditOAuth.AUTHORIZE_URL_FALLBACK)
                    return
                }
                phase = Phase.Failed(getString(R.string.wallpaper_reddit_login_page_error), retry = true)
            }
        }
        webView = w
        load(w, RedditOAuth.AUTHORIZE_URL)
        w.requestFocus()
        return w
    }

    private fun load(view: WebView, base: String) {
        val url = RedditOAuth.authorizeUrl(oauthState, base)
        Log.i(TAG, "Opening the Reddit authorize page ${RedditOAuth.redact(url).replace(oauthState, "<state>")}")
        view.loadUrl(url)
    }

    private fun handleRedirect(url: String) {
        if (phase != Phase.Browsing) return
        Log.i(TAG, "Reddit redirect ${RedditOAuth.redact(url).replace(oauthState, "<state>")}")
        when (val r = RedditOAuth.classifyRedirect(url, oauthState)) {
            is RedirectResult.Exchange -> exchange(r.code)
            RedirectResult.StateMismatch -> phase = Phase.Failed(getString(R.string.wallpaper_reddit_login_state_mismatch), retry = true)
            RedirectResult.AccessDenied -> phase = Phase.Failed(getString(R.string.wallpaper_reddit_login_denied), retry = true)
            is RedirectResult.Error -> phase = Phase.Failed(getString(R.string.wallpaper_reddit_login_failed, r.error), retry = true)
            RedirectResult.NotRedirect -> Unit
        }
    }

    private fun exchange(code: String) {
        phase = Phase.Exchanging
        val module = container.wallpaper
        lifecycleScope.launch {
            when (val result = module.redditApi.exchangeCode(code)) {
                is TokenResult.Success -> {
                    module.onRedditLoggedIn()
                    setResult(Activity.RESULT_OK)
                    finish()
                }
                is TokenResult.NetworkError -> phase = Phase.Failed(getString(R.string.wallpaper_reddit_login_network), retry = true)
                TokenResult.InvalidGrant -> phase = Phase.Failed(getString(R.string.wallpaper_reddit_login_failed, "invalid_grant"), retry = true)
                is TokenResult.RedditError -> phase = Phase.Failed(getString(R.string.wallpaper_reddit_login_failed, result.error), retry = true)
                is TokenResult.HttpError -> phase = Phase.Failed(getString(R.string.wallpaper_reddit_login_failed, "HTTP ${result.code}"), retry = true)
                TokenResult.Malformed -> phase = Phase.Failed(getString(R.string.wallpaper_reddit_login_failed, "bad reply"), retry = true)
            }
        }
    }

    private fun restart() {
        oauthState = RedditOAuth.newState()
        triedFallback = false
        webView?.let {
            it.stopLoading()
            it.destroy()
        }
        webView = null
        phase = if (hasWebView()) Phase.Browsing else Phase.NoWebView
    }

    private fun cancel() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    companion object {
        private const val TAG = "QLRedditLogin"

        /** Reddit's login page is drawn at half size: at 2x density it overflows a 1080p screen. */
        private const val PAGE_SCALE = 0.5f
        private const val KEY_STATE = "oauth_state"

        /** The login screen. Logging in does not change the wallpaper source. */
        fun intent(context: Context): Intent = Intent(context, RedditLoginActivity::class.java)
    }
}

/**
 * A WebView that moves through a page with the D-pad: DOWN and UP act as Tab and Shift+Tab, so
 * focus visits every link, button and field in order (Reddit's are web components, which the
 * WebView's own spatial navigation skips). LEFT and RIGHT keep their usual meaning, such as
 * moving the cursor in a field.
 */
private class TvWebView(context: Context) : WebView(context) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val back = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> false
            KeyEvent.KEYCODE_DPAD_UP -> true
            else -> return super.dispatchKeyEvent(event)
        }
        val meta = if (back) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        val tab = KeyEvent(
            event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_TAB, event.repeatCount, meta,
            event.deviceId, event.scanCode, event.flags, event.source,
        )
        return super.dispatchKeyEvent(tab)
    }
}

/**
 * Lays its child out at 1/[scale] of its own size and draws it scaled by [scale], so a web page
 * gets a viewport 1/[scale] times larger in CSS px and everything on it is drawn [scale] times
 * smaller.
 */
private class ScaledHost(context: Context, private val scale: Float) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val cw = MeasureSpec.makeMeasureSpec((w / scale).roundToInt(), MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec((h / scale).roundToInt(), MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.layout(0, 0, c.measuredWidth, c.measuredHeight)
            c.pivotX = 0f
            c.pivotY = 0f
            c.scaleX = scale
            c.scaleY = scale
        }
    }
}
