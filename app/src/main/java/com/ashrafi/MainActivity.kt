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
        private const val SITE_HOST = "YOURSITE.COM"
        private const val HOME_URL = "HTTPS://YOURSITE.com"
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
            cacheMode = if (online) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK

            userAgentString = "$userAgentString AshrafiApp/1.0"
        }

        // Disable text selection — native app feel
        webView.setOnLongClickListener { true }
        webView.isLongClickable = false

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false
                val host = url.host ?: return false
                return if (!host.contains(SITE_HOST)) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (_: Exception) {}
                    true
                } else {
                    false
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                onLoadingChanged(true)
                view?.settings?.cacheMode = if (isOnline()) {
                    WebSettings.LOAD_DEFAULT
                } else {
                    WebSettings.LOAD_CACHE_ELSE_NETWORK
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                onLoadingChanged(false)

                // If we loaded from cache while offline, show the soft banner
                if (!isOnline()) {
                    onCacheLoadedOffline()
                }

                if (isFirstPageLoad) {
                    isFirstPageLoad = false
                    onFirstLoadComplete()
                }

                injectPrefetchScript(view)
                disableTextSelection(view)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (
                    request?.isForMainFrame == true &&
                    (
                        error?.errorCode == WebViewClient.ERROR_HOST_LOOKUP ||
                        error?.errorCode == WebViewClient.ERROR_CONNECT ||
                        error?.errorCode == WebViewClient.ERROR_TIMEOUT
                    )
                ) {
                    onHardError()
                }
            }
        }
    }

    private fun disableTextSelection(view: WebView?) {
        view?.evaluateJavascript(
            """
            (function() {
                var style = document.createElement('style');
                style.textContent = '* { -webkit-user-select: none !important; user-select: none !important; }';
                document.head?.appendChild(style);
            })();
            """.trimIndent(), null
        )
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (wv != null && wv.canGoBack() && wv.url != HOME_URL) {
                    wv.goBack()
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastBackPressTime < DOUBLE_BACK_EXIT_TIME) {
                        finish()
                    } else {
                        lastBackPressTime = now
                        Toast.makeText(
                            this@MainActivity,
                            "برای خروج دوباره بازگشت را بزنید",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        })
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _isNetworkAvailable.value = true
            }
            override fun onLost(network: Network) {
                _isNetworkAvailable.value = false
            }
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                _isNetworkAvailable.value = networkCapabilities
                    .hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    networkCapabilities
                    .hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }
        cm.registerNetworkCallback(request, networkCallback!!)
    }

    private fun injectPrefetchScript(webView: WebView?) {
        val js = """
            (function() {
                try {
                    var links = document.querySelectorAll('a[href]');
                    var prefetched = 0;
                    var seen = {};
                    for (var i = 0; i < links.length && prefetched < $MAX_PREFETCH_LINKS; i++) {
                        var href = links[i].href;
                        if (href && href.indexOf('$SITE_HOST') !== -1 && !seen[href]) {
                            seen[href] = true;
                            var link = document.createElement('link');
                            link.rel = 'prefetch';
                            link.href = href;
                            document.head.appendChild(link);
                            prefetched++;
                        }
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        webView?.evaluateJavascript(js, null)
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView?.restoreState(savedInstanceState)
    }

    override fun onDestroy() {
        super.onDestroy()
        networkCallback?.let {
            (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(it)
        }
    }
}

// ──────────────────────────────────────────────
// Branded splash screen (first load only)
// ──────────────────────────────────────────────
@Composable
fun SplashScreen(creamColor: Color, brownColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(creamColor),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "لوگو",
                modifier = Modifier.size(140.dp)
            )
            Spacer(modifier = Modifier.height(32.dp))
            CircularProgressIndicator(
                color = brownColor,
                strokeWidth = 3.dp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "در حال بارگذاری...",
                color = brownColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ──────────────────────────────────────────────
// Hard offline error — no cache available
// ──────────────────────────────────────────────
@Composable
fun OfflineScreen(
    countdown: Int,
    creamColor: Color,
    brownColor: Color,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(creamColor),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "لوگو",
                modifier = Modifier.size(100.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Network icon
            Surface(
                shape = RoundedCornerShape(50),
                color = Color(0xFFEFE0D0),
                modifier = Modifier.size(80.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = "📡", fontSize = 36.sp)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "خطای شبکه",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4E342E),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "اتصال اینترنت برقرار نیست.\nلطفاً وای‌فای یا داده موبایل را بررسی کنید.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF795548),
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = brownColor),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth(0.65f)
                    .height(50.dp)
            ) {
                Text(
                    "تلاش مجدد",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "تلاش خودکار تا $countdown ثانیه دیگر",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFBCAAA4),
                textAlign = TextAlign.Center
            )
        }
    }
}

// ──────────────────────────────────────────────
// Soft offline banner (cache loaded, no internet)
// ──────────────────────────────────────────────
@Composable
fun OfflineBanner() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFEF6C00),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "⚠️", fontSize = 18.sp)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "اتصال اینترنت را بررسی کنید. محتوا از حافظه نمایش داده می‌شود.",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 18.sp
            )
        }
    }
}
