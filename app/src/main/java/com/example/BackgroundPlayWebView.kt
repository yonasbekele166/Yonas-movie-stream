package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Native bridge allowing JavaScript listeners on YouTube to:
 * 1. Request the Android InputMethodManager (soft keyboard).
 * 2. Report HTML5 video play/pause events back to YouTubePlaybackController & MediaSession.
 */
class AndroidInputBridge(private val webView: WebView) {
    @JavascriptInterface
    fun showKeyboard() {
        webView.post {
            if (!webView.hasFocus()) {
                webView.requestFocus()
            }
            val imm = webView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    @JavascriptInterface
    fun hideKeyboard() {
        webView.post {
            val imm = webView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(webView.windowToken, 0)
        }
    }

    @JavascriptInterface
    fun onPlaybackStateChanged(playing: Boolean) {
        webView.post {
            YouTubePlaybackController.setPlaybackState(playing)
        }
    }
}

/**
 * Custom WebView designed for background audio playback with full MediaSession controls:
 * 1. Registers with YouTubePlaybackController for Play/Pause/Next/Prev notification events.
 * 2. Focusable in touch mode with touch focus transfer so Android IME soft keyboard opens properly.
 * 3. Overrides window visibility changes so Chromium never pauses HTML5 audio/video in background.
 * 4. Injects JS to sync playback state with the notification and lock screen controls.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
class BackgroundPlayWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    companion object {
        const val MOBILE_CHROME_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

        // Script to override YouTube's Page Visibility API, sync playback with MediaSession, and handle input focus
        const val BACKGROUND_AUDIO_JS = """
            (function() {
                try {
                    // 1. Force document.hidden and visibilityState to remain visible
                    Object.defineProperty(document, 'hidden', {
                        get: function() { return false; },
                        configurable: true
                    });
                    Object.defineProperty(document, 'visibilityState', {
                        get: function() { return 'visible'; },
                        configurable: true
                    });

                    // 2. Prevent visibilitychange events from triggering automated video pause
                    window.addEventListener('visibilitychange', function(e) {
                        e.stopImmediatePropagation();
                    }, true);
                    document.addEventListener('visibilitychange', function(e) {
                        e.stopImmediatePropagation();
                    }, true);

                    // 3. Connect HTML inputs (search bar, text inputs) to native Android keyboard
                    function triggerSoftKeyboard() {
                        if (window.AndroidInputBridge && window.AndroidInputBridge.showKeyboard) {
                            window.AndroidInputBridge.showKeyboard();
                        }
                    }

                    document.addEventListener('focusin', function(e) {
                        var target = e.target;
                        if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) {
                            triggerSoftKeyboard();
                        }
                    }, false);

                    document.addEventListener('click', function(e) {
                        var target = e.target;
                        if (target) {
                            var input = target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' ? target : target.closest('input, textarea, [role="search"], .searchbox, .search-box, button[aria-label*="Search"], button[aria-label*="search"]');
                            if (input) {
                                triggerSoftKeyboard();
                            }
                        }
                    }, false);

                    // 4. Synchronize HTML5 video play/pause events with MediaSession and notification
                    function syncPlaybackWithNotification() {
                        var v = document.querySelector('video');
                        if (v && !v.__syncedWithApp) {
                            v.__syncedWithApp = true;
                            v.addEventListener('play', function() {
                                if (window.AndroidInputBridge && window.AndroidInputBridge.onPlaybackStateChanged) {
                                    window.AndroidInputBridge.onPlaybackStateChanged(true);
                                }
                            });
                            v.addEventListener('playing', function() {
                                if (window.AndroidInputBridge && window.AndroidInputBridge.onPlaybackStateChanged) {
                                    window.AndroidInputBridge.onPlaybackStateChanged(true);
                                }
                            });
                            v.addEventListener('pause', function() {
                                if (window.AndroidInputBridge && window.AndroidInputBridge.onPlaybackStateChanged) {
                                    window.AndroidInputBridge.onPlaybackStateChanged(false);
                                }
                            });
                        }
                    }
                    setInterval(syncPlaybackWithNotification, 1000);

                } catch (err) {
                    console.error('BackgroundPlay script error:', err);
                }
            })();
        """
    }

    init {
        setupWebView()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(View.VISIBLE)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, View.VISIBLE)
    }

    private fun setupWebView() {
        setLayerType(View.LAYER_TYPE_NONE, null)

        // Register with playback controller for notification buttons
        YouTubePlaybackController.registerWebView(this)

        // Essential for soft keyboard (IME) input inside Jetpack Compose AndroidView
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus(View.FOCUS_DOWN)

        setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_UP) {
                if (!v.hasFocus()) {
                    v.requestFocus()
                }
            }
            false
        }

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = MOBILE_CHROME_USER_AGENT
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(this@BackgroundPlayWebView, true)
        }

        // Register the input bridge for native soft keyboard requests and playback state
        addJavascriptInterface(AndroidInputBridge(this), "AndroidInputBridge")

        webChromeClient = object : WebChromeClient() {
            override fun getDefaultVideoPoster(): android.graphics.Bitmap? {
                return android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
            }
        }

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                injectBackgroundAudioFix(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectBackgroundAudioFix(view)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: ""
                // Keep all links within the WebView; prevent redirection to external native YouTube app
                if (url.startsWith("intent:") || url.startsWith("vnd.youtube:")) {
                    return true
                }
                return false
            }
        }
    }

    private fun injectBackgroundAudioFix(view: WebView?) {
        view?.evaluateJavascript(BACKGROUND_AUDIO_JS, null)
    }

    override fun destroy() {
        YouTubePlaybackController.unregisterWebView()
        super.destroy()
    }
}
