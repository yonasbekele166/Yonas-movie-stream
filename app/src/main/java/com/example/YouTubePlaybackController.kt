package com.example

import android.webkit.WebView
import java.lang.ref.WeakReference

/**
 * Coordinates media playback between the Background Foreground Service / MediaSession
 * and the HTML5 video element inside the YouTube WebView.
 */
object YouTubePlaybackController {
    var webViewRef: WeakReference<WebView>? = null
    var isPlaying: Boolean = true
        private set

    var onStateChanged: ((Boolean) -> Unit)? = null

    fun registerWebView(webView: WebView) {
        webViewRef = WeakReference(webView)
    }

    fun unregisterWebView() {
        webViewRef = null
    }

    fun setPlaybackState(playing: Boolean) {
        if (isPlaying != playing) {
            isPlaying = playing
            onStateChanged?.invoke(playing)
        }
    }

    fun play() {
        isPlaying = true
        executeJs("""
            (function() {
                var v = document.querySelector('video');
                if (v && v.paused) {
                    window.__isUserInitiatedPause = false;
                    v.play();
                } else {
                    var playBtn = document.querySelector('.ytp-play-button, .player-control-play, button[aria-label*="Play"]');
                    if (playBtn) playBtn.click();
                }
            })();
        """.trimIndent())
        onStateChanged?.invoke(true)
    }

    fun pause() {
        isPlaying = false
        executeJs("""
            (function() {
                var v = document.querySelector('video');
                if (v && !v.paused) {
                    window.__isUserInitiatedPause = true;
                    v.pause();
                } else {
                    var pauseBtn = document.querySelector('.ytp-play-button, .player-control-pause, button[aria-label*="Pause"]');
                    if (pauseBtn) pauseBtn.click();
                }
            })();
        """.trimIndent())
        onStateChanged?.invoke(false)
    }

    fun skipNext() {
        executeJs("""
            (function() {
                var nextBtn = document.querySelector('.ytp-next-button, button[aria-label*="Next"]');
                if (nextBtn) {
                    nextBtn.click();
                } else {
                    var v = document.querySelector('video');
                    if (v) {
                        v.currentTime = Math.min((v.duration || v.currentTime + 10), v.currentTime + 10);
                    }
                }
            })();
        """.trimIndent())
    }

    fun skipPrevious() {
        executeJs("""
            (function() {
                var prevBtn = document.querySelector('.ytp-prev-button, button[aria-label*="Previous"]');
                if (prevBtn) {
                    prevBtn.click();
                } else {
                    var v = document.querySelector('video');
                    if (v) {
                        v.currentTime = Math.max(0, v.currentTime - 10);
                    }
                }
            })();
        """.trimIndent())
    }

    private fun executeJs(script: String) {
        val webView = webViewRef?.get() ?: return
        webView.post {
            webView.evaluateJavascript(script, null)
        }
    }
}
