package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF141414))) {
        StreamFlixScreen()
      }
    }
  }
}

/**
 * JavaScript Interface enabling index.html to control system immersive full-screen,
 * orientation, and launch YouTube background player directly.
 */
class AndroidBridge(
  private val activity: Activity,
  private val onOpenYouTube: () -> Unit
) {
  @JavascriptInterface
  fun launchYouTubePlayer() {
    activity.runOnUiThread {
      onOpenYouTube()
    }
  }

  @JavascriptInterface
  fun setLandscape(enableLandscape: Boolean) {
    activity.runOnUiThread {
      if (enableLandscape) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBars(activity)
      } else {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        showSystemBars(activity)
      }
    }
  }

  @JavascriptInterface
  fun setImmersiveFullscreen(enableFullscreen: Boolean) {
    activity.runOnUiThread {
      if (enableFullscreen) {
        hideSystemBars(activity)
      } else {
        showSystemBars(activity)
      }
    }
  }

  @JavascriptInterface
  fun setKeepScreenOn(keepOn: Boolean) {
    activity.runOnUiThread {
      val window = activity.window ?: return@runOnUiThread
      if (keepOn) {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      } else {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      }
    }
  }
}

fun hideSystemBars(activity: Activity) {
  val window = activity.window ?: return
  val insetsController = WindowCompat.getInsetsController(window, window.decorView)
  insetsController.systemBarsBehavior =
    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
  insetsController.hide(WindowInsetsCompat.Type.systemBars())
}

fun showSystemBars(activity: Activity) {
  val window = activity.window ?: return
  val insetsController = WindowCompat.getInsetsController(window, window.decorView)
  insetsController.show(WindowInsetsCompat.Type.systemBars())
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun StreamFlixScreen() {
  val context = LocalContext.current
  val activity = context as? Activity
  var webViewInstance by remember { mutableStateOf<WebView?>(null) }
  var customVideoView by remember { mutableStateOf<View?>(null) }
  var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

  var isYouTubePlayerVisible by remember { mutableStateOf(false) }
  var youtubeWebViewInstance by remember { mutableStateOf<BackgroundPlayWebView?>(null) }

  // Notification permission launcher for Android 13+ foreground service notification
  val notificationPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) { _ -> }

  fun openYouTube() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    YouTubeBackgroundService.startService(context)
    isYouTubePlayerVisible = true
  }

  // Handle system back navigation
  BackHandler {
    if (isYouTubePlayerVisible) {
      if (youtubeWebViewInstance?.canGoBack() == true) {
        youtubeWebViewInstance?.goBack()
      } else {
        isYouTubePlayerVisible = false
      }
    } else if (customVideoView != null) {
      customViewCallback?.onCustomViewHidden()
      customVideoView = null
      customViewCallback = null
      activity?.let { act ->
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        showSystemBars(act)
      }
    } else {
      webViewInstance?.let { webView ->
        // Evaluate if any JS modal is active in StreamFlix and close it first
        webView.evaluateJavascript(
          """
          (function() {
            var videoModal = document.getElementById('video-modal');
            var seriesModal = document.getElementById('series-modal');
            var detailsModal = document.getElementById('details-modal');
            var searchView = document.getElementById('search-view');
            var myListView = document.getElementById('my-list-view');
            if (videoModal && !videoModal.classList.contains('hidden')) {
              closeVideoModal();
              return true;
            } else if (seriesModal && !seriesModal.classList.contains('hidden')) {
              closeSeriesModal();
              return true;
            } else if (detailsModal && !detailsModal.classList.contains('hidden')) {
              closeDetailsModal();
              return true;
            } else if (searchView && !searchView.classList.contains('hidden')) {
              closeSearchView();
              return true;
            } else if (myListView && !myListView.classList.contains('hidden')) {
              showHomeView();
              return true;
            }
            return false;
          })();
          """.trimIndent()
        ) { handled ->
          if (handled != "true") {
            if (webView.canGoBack()) {
              webView.goBack()
            }
          }
        }
      }
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color(0xFF141414))
      .testTag("streamflix_main_container")
  ) {
    if (isYouTubePlayerVisible) {
      // --- Dedicated YouTube Background Player Container ---
      Column(
        modifier = Modifier
          .fillMaxSize()
          .background(Color(0xFF0F0F0F))
          .statusBarsPadding()
          .testTag("youtube_background_player_container")
      ) {
        // Player Header Bar
        Surface(
          color = Color(0xFF1F1F1F),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Box(
              modifier = Modifier
                .size(32.dp)
                .background(Color(0xFFFF0000), shape = RoundedCornerShape(8.dp)),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "YouTube",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
              )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "YouTube Background Player",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
              )
              Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                  modifier = Modifier
                    .size(6.dp)
                    .background(Color(0xFF22C55E), shape = CircleShape)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  text = "Background Audio Active",
                  color = Color(0xFFA1A1AA),
                  fontSize = 11.sp
                )
              }
            }

            IconButton(
              onClick = { youtubeWebViewInstance?.reload() },
              modifier = Modifier.testTag("youtube_reload_button")
            ) {
              Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Reload",
                tint = Color.White
              )
            }

            IconButton(
              onClick = { isYouTubePlayerVisible = false },
              modifier = Modifier.testTag("youtube_close_button")
            ) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close Player",
                tint = Color.White
              )
            }
          }
        }

        // Dedicated BackgroundPlayWebView loading m.youtube.com
        AndroidView(
          factory = { ctx ->
            BackgroundPlayWebView(ctx).apply {
              layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
              )
              isFocusable = true
              isFocusableInTouchMode = true
              loadUrl("https://m.youtube.com/")
              youtubeWebViewInstance = this
            }
          },
          modifier = Modifier
            .fillMaxSize()
            .weight(1f)
            .testTag("youtube_webview")
        )
      }
    } else if (customVideoView != null) {
      // Fullscreen video player layer
      AndroidView(
        factory = { ctx ->
          FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.BLACK)
            customVideoView?.let { video ->
              (video.parent as? ViewGroup)?.removeView(video)
              addView(video)
            }
          }
        },
        modifier = Modifier.fillMaxSize().testTag("fullscreen_video_container")
      )
    } else {
      // Standard streaming app view
      AndroidView(
        factory = { ctx ->
          WebView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.parseColor("#141414"))
            setLayerType(View.LAYER_TYPE_NONE, null)

            // Register native bridge for fullscreen, landscape, and YouTube player launch
            activity?.let { act ->
              addJavascriptInterface(AndroidBridge(act, onOpenYouTube = { openYouTube() }), "AndroidBridge")
            }

            // Pre-create cache directories to prevent Chromium simple_file_enumerator opendir errors
            try {
              val codeCache = java.io.File(ctx.cacheDir, "WebView/Default/HTTP Cache/Code Cache")
              java.io.File(codeCache, "wasm").mkdirs()
              java.io.File(codeCache, "js").mkdirs()
            } catch (_: Exception) {}

            @Suppress("DEPRECATION")
            settings.apply {
              javaScriptEnabled = true
              domStorageEnabled = true
              databaseEnabled = true
              allowFileAccess = true
              allowContentAccess = true
              allowFileAccessFromFileURLs = true
              allowUniversalAccessFromFileURLs = true
              mediaPlaybackRequiresUserGesture = false
              loadWithOverviewMode = true
              useWideViewPort = true
              mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
              cacheMode = WebSettings.LOAD_DEFAULT
              userAgentString = userAgentString.replace("; wv", "")
              setSupportMultipleWindows(false)
              javaScriptCanOpenWindowsAutomatically = false
            }

            webChromeClient = object : WebChromeClient() {
              override fun getDefaultVideoPoster(): android.graphics.Bitmap? {
                return android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
              }

              override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                customVideoView = view
                customViewCallback = callback
                activity?.let { act ->
                  act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                  hideSystemBars(act)
                }
              }

              override fun onHideCustomView() {
                customVideoView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                activity?.let { act ->
                  act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                  showSystemBars(act)
                }
              }

              override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
              ): Boolean {
                return false
              }

              override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                android.util.Log.d("StreamFlixJS", "${consoleMessage?.message()} (line ${consoleMessage?.lineNumber()})")
                return true
              }
            }

            webViewClient = object : WebViewClient() {
              override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
              ): Boolean {
                val url = request?.url?.toString() ?: ""
                val isMainFrame = request?.isForMainFrame ?: false

                if (isMainFrame) {
                  if (url.startsWith("file://") || url.startsWith("https://api.themoviedb.org")) {
                    return false
                  }
                  if (url.contains("vidlink.pro") || url.contains("vidsrc") || url.contains("autoembed")) {
                    return false
                  }
                  android.util.Log.d("StreamFlix", "Prevented ad redirect on main frame: $url")
                  return true
                }

                if (url.startsWith("market:") || url.startsWith("intent:") || url.startsWith("android-app:")) {
                  return true
                }

                return false
              }
            }

            loadUrl("file:///android_asset/index.html")
            webViewInstance = this
          }
        },
        modifier = Modifier.fillMaxSize().testTag("streamflix_webview")
      )
    }
  }

  DisposableEffect(Unit) {
    onDispose {
      webViewInstance?.destroy()
      youtubeWebViewInstance?.destroy()
    }
  }
}
