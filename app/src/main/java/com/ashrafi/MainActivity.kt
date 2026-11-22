package com.YOUR APP NAME

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var lastBackPressTime: Long = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Shared state updated from network callback
    private val _isNetworkAvailable = mutableStateOf(false)

    companion object {
        private const val SITE_HOST = "yaldamod.ir"
        private const val HOME_URL = "https://yaldamod.ir/"
        private const val RETRY_INTERVAL_SECONDS = 30
        private const val DOUBLE_BACK_EXIT_TIME = 2000L
        private const val MAX_PREFETCH_LINKS = 6
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupBackPressHandler()
        registerNetworkCallback()

        // Seed initial network state
        _isNetworkAvailable.value = isOnline()

        setContent {
            val isNetworkAvailable by _isNetworkAvailable

            var isLoading by remember { mutableStateOf(true) }
            var isFirstLoad by remember { mutableStateOf(true) }
            // true = no network AND no cache (show full error screen)
            var hasHardError by remember { mutableStateOf(false) }
            // true = cache was loaded but no internet (show top banner)
            var showOfflineBanner by remember { mutableStateOf(false) }
            var retryAttempt by remember { mutableIntStateOf(0) }
            var retryCountdown by remember { mutableIntStateOf(RETRY_INTERVAL_SECONDS) }

            val creamColor = Color(0xFFF5E6D3)
            val brownColor = Color(0xFF8D6E63)

            // When network comes back and banner is showing → auto-reload
            LaunchedEffect(isNetworkAvailable) {
                if (isNetworkAvailable && showOfflineBanner) {
                    showOfflineBanner = false
                    isLoading = true
                    webView?.settings?.cacheMode = WebSettings.LOAD_DEFAULT
                    webView?.reload()
                }
            }

            // Limit splash screen to 2 seconds — show site even if still loading
            LaunchedEffect(Unit) {
                delay(2000L)
                isFirstLoad = false
            }

            // Auto-retry every 30 seconds when in hard error state
            LaunchedEffect(hasHardError, retryAttempt) {
                if (hasHardError) {
                    retryCountdown = RETRY_INTERVAL_SECONDS
                    while (retryCountdown > 0) {
                        delay(1000L)
                        retryCountdown--
                    }
                    if (isOnline()) {
                        hasHardError = false
                        isLoading = true
                        webView?.settings?.cacheMode = WebSettings.LOAD_DEFAULT
                        webView?.reload()
                    } else {
                        retryAttempt++
                    }
                }
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(modifier = Modifier.fillMaxSize()) {

                // WebView — always in composition tree
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            webView = this
                            setupWebView(
                                webView = this,
                                onLoadingChanged = { isLoading = it },
                                onHardError = {
                                    hasHardError = true
                                    isLoading = false
                                },
                                onCacheLoadedOffline = {
                                    showOfflineBanner = true
                                },
                                onFirstLoadComplete = { isFirstLoad = false }
                            )

                            if (savedInstanceState != null) {
                                val restored = restoreState(savedInstanceState)
                                if (restored == null) loadUrl(HOME_URL)
                            } else {
                                loadUrl(HOME_URL)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Thin progress bar for subsequent page loads
                if (isLoading && !isFirstLoad && !hasHardError) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .height(3.dp),
                        color = brownColor,
                        trackColor = creamColor
                    )
                }

                // Branded splash screen for first load
                AnimatedVisibility(
                    visible = isLoading && isFirstLoad && !hasHardError,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    SplashScreen(creamColor = creamColor, brownColor = brownColor)
                }

                // Hard offline error (no cache available)
                AnimatedVisibility(
                    visible = hasHardError,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    OfflineScreen(
                        countdown = retryCountdown,
                        creamColor = creamColor,
                        brownColor = brownColor,
                        onRetry = {
                            if (isOnline()) {
                                hasHardError = false
                                isLoading = true
                                webView?.settings?.cacheMode = WebSettings.LOAD_DEFAULT
                                webView?.reload()
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    "اینترنت در دسترس نیست",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                }

                // Soft offline banner (cache loaded, no internet)
                AnimatedVisibility(
                    visible = showOfflineBanner && !hasHardError,
                    modifier = Modifier.align(Alignment.TopCenter),
                    enter = slideInVertically { -it },
                    exit = slideOutVertically { -it }
                ) {
                    OfflineBanner()
                }
            }
            } // end CompositionLocalProvider
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(
        webView: WebView,
        onLoadingChanged: (Boolean) -> Unit,
        onHardError: () -> Unit,
        onCacheLoadedOffline: () -> Unit,
        onFirstLoadComplete: () -> Unit
    ) {
        var isFirstPageLoad = true
        val online = isOnline()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            allowContentAccess = true
            allowFileAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            setSupportMultipleWindows(false)

            // Disable zoom — native-app feel
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mediaPlaybackRequiresUserGesture = false

            // Speed: aggressive caching
            cacheMode = if (online) WebSettings.LOAD_
