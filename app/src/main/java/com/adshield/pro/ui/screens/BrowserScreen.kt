package com.adshield.pro.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.adshield.pro.data.local.preferences.AppSettingsState
import com.adshield.pro.engine.browser.BrowserContentBlocker
import com.adshield.pro.ui.theme.AlertRed
import com.adshield.pro.ui.theme.CyberCyan
import com.adshield.pro.ui.theme.EmeraldPrimary
import com.adshield.pro.ui.theme.WarningAmber

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    settings: AppSettingsState,
    browserContentBlocker: BrowserContentBlocker,
    onToggleSiteException: (String, Boolean) -> Unit,
    onUpdateBrowserPrivacy: (Boolean, Boolean, Boolean, Boolean) -> Unit,
    onAddCustomCosmeticSelector: (String) -> Unit,
    onShowFeedback: (String) -> Unit
) {
    val context = LocalContext.current
    var urlInput by remember { mutableStateOf("https://example.com") }
    var currentPageUrl by remember { mutableStateOf("https://example.com") }
    var pageLoadProgress by remember { mutableIntStateOf(100) }
    var blockedSubresources by remember { mutableLongStateOf(browserContentBlocker.getBlockedSubresourceCount()) }
    var blockedPopups by remember { mutableLongStateOf(browserContentBlocker.getBlockedPopupCount()) }
    var strippedTrackers by remember { mutableLongStateOf(browserContentBlocker.getStrippedTrackerParamCount()) }
    var showBrowserShieldSheet by remember { mutableStateOf(false) }

    val currentHost = remember(currentPageUrl) {
        runCatching { Uri.parse(currentPageUrl).host?.lowercase()?.removePrefix("www.") }.getOrNull() ?: ""
    }
    val isCurrentSiteAllowlisted = remember(currentHost, settings.browserSiteExceptions) {
        currentHost.isNotEmpty() && browserContentBlocker.isHostInExceptionSet(currentHost, settings.browserSiteExceptions)
    }

    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef.value?.stopLoading()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Browser Address & Shield Toolbar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { webViewRef.value?.goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    IconButton(onClick = { webViewRef.value?.goForward() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                    }

                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = if (currentPageUrl.startsWith("https://")) EmeraldPrimary else WarningAmber,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                val normalizedUrl = normalizeNavigationInput(urlInput)
                                val sanitizedUrl = browserContentBlocker.sanitizeTrackingParameters(normalizedUrl)
                                urlInput = sanitizedUrl
                                webViewRef.value?.loadUrl(sanitizedUrl, mapOf("DNT" to "1", "Sec-GPC" to "1"))
                            }
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(onClick = { webViewRef.value?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
                    }

                    IconButton(onClick = { showBrowserShieldSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Browser Shield Controls",
                            tint = EmeraldPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Live Layer-3 Browser Telemetry Strip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Blocked Ads: $blockedSubresources",
                            style = MaterialTheme.typography.labelMedium,
                            color = AlertRed,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Pop-ups: $blockedPopups",
                            style = MaterialTheme.typography.labelMedium,
                            color = WarningAmber
                        )
                        Text(
                            text = "Params Stripped: $strippedTrackers",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyberCyan
                        )
                    }

                    if (currentHost.isNotEmpty()) {
                        Surface(
                            color = if (isCurrentSiteAllowlisted) WarningAmber.copy(alpha = 0.2f) else EmeraldPrimary.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                text = if (isCurrentSiteAllowlisted) "Site Allowlisted" else "Cosmetic Shield ON",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isCurrentSiteAllowlisted) WarningAmber else EmeraldPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                if (pageLoadProgress in 1..99) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { pageLoadProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = EmeraldPrimary
                    )
                }
            }
        }

        // WebView Content Area
        val appSettings = settings
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx ->
                WebView(ctx).also { webView ->
                    webViewRef.value = webView
                    webView.settings.apply {
                        javaScriptEnabled = appSettings.browserJavaScriptEnabled
                        domStorageEnabled = true
                        databaseEnabled = false
                        setSupportMultipleWindows(true) // Enables WebChromeClient.onCreateWindow pop-up interception
                        javaScriptCanOpenWindowsAutomatically = false
                        mediaPlaybackRequiresUserGesture = true
                        allowFileAccess = false
                        allowContentAccess = false
                    }

                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(
                        webView,
                        !appSettings.browserBlockThirdPartyCookies
                    )

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val target = request?.url?.toString() ?: return false
                            val sanitized = browserContentBlocker.sanitizeTrackingParameters(target)
                            strippedTrackers = browserContentBlocker.getStrippedTrackerParamCount()
                            if (sanitized != target) {
                                view?.loadUrl(sanitized, mapOf("DNT" to "1", "Sec-GPC" to "1"))
                                return true
                            }
                            return false
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            val decision = browserContentBlocker.evaluateRequest(
                                requestUrl = reqUrl,
                                pageHost = currentHost,
                                allowlistedSites = appSettings.browserSiteExceptions
                            )
                            blockedSubresources = browserContentBlocker.getBlockedSubresourceCount()
                            return if (decision.shouldBlock) {
                                browserContentBlocker.createEmptyBlockedResponse()
                            } else {
                                null
                            }
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            if (url != null) {
                                currentPageUrl = url
                                urlInput = url
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (appSettings.browserCosmeticFilteringEnabled && view != null) {
                                val host = runCatching { Uri.parse(url).host }.getOrNull()
                                val script = browserContentBlocker.buildCosmeticFilterScript(
                                    pageHost = host,
                                    allowlistedSites = appSettings.browserSiteExceptions,
                                    customSelectors = appSettings.customCosmeticSelectors
                                )
                                if (script != null) {
                                    view.evaluateJavascript(script, null)
                                }
                            }
                        }
                    }

                    webView.webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            pageLoadProgress = newProgress
                        }

                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message?
                        ): Boolean {
                            if (appSettings.browserPopupBlockingEnabled && !isUserGesture) {
                                browserContentBlocker.recordPopupBlocked()
                                blockedPopups = browserContentBlocker.getBlockedPopupCount()
                                return false
                            }
                            return super.onCreateWindow(view, isDialog, isUserGesture, resultMsg)
                        }
                    }

                    webView.loadUrl(currentPageUrl, mapOf("DNT" to "1", "Sec-GPC" to "1"))
                }
            },
            update = { webView ->
                webView.settings.javaScriptEnabled = appSettings.browserJavaScriptEnabled
                CookieManager.getInstance().setAcceptThirdPartyCookies(
                    webView,
                    !appSettings.browserBlockThirdPartyCookies
                )
            }
        )
    }

    if (showBrowserShieldSheet) {
        var customCssSelector by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showBrowserShieldSheet = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = EmeraldPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Browser protection settings")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Built-in WebView protections apply to pages opened inside AdShield Pro Browser. (Note: Non-root Android apps cannot inject cosmetic CSS into external third-party browsers.)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (currentHost.isNotEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Pause Blocking on $currentHost",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = "Website-specific allowlist exception",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                Switch(
                                    checked = isCurrentSiteAllowlisted,
                                    onCheckedChange = {
                                        onToggleSiteException(currentHost, it)
                                        webViewRef.value?.reload()
                                    }
                                )
                            }
                        }
                    }

                    BrowserSwitchRow(
                        title = "Cosmetic Element Hiding",
                        subtitle = "Collapses ad placeholders & sponsored DOM containers",
                        checked = settings.browserCosmeticFilteringEnabled,
                        onCheckedChange = {
                            onUpdateBrowserPrivacy(
                                it,
                                settings.browserPopupBlockingEnabled,
                                settings.browserBlockThirdPartyCookies,
                                settings.browserJavaScriptEnabled
                            )
                        }
                    )

                    BrowserSwitchRow(
                        title = "Pop-up & Redirect Blocker",
                        subtitle = "Blocks background window.open() pop-unders",
                        checked = settings.browserPopupBlockingEnabled,
                        onCheckedChange = {
                            onUpdateBrowserPrivacy(
                                settings.browserCosmeticFilteringEnabled,
                                it,
                                settings.browserBlockThirdPartyCookies,
                                settings.browserJavaScriptEnabled
                            )
                        }
                    )

                    BrowserSwitchRow(
                        title = "Block Third-Party Cookies",
                        subtitle = "Prevents cross-site cookie tracking",
                        checked = settings.browserBlockThirdPartyCookies,
                        onCheckedChange = {
                            onUpdateBrowserPrivacy(
                                settings.browserCosmeticFilteringEnabled,
                                settings.browserPopupBlockingEnabled,
                                it,
                                settings.browserJavaScriptEnabled
                            )
                        }
                    )

                    BrowserSwitchRow(
                        title = "Enable JavaScript",
                        subtitle = "Disable on untrusted sites for maximum script lockdown",
                        checked = settings.browserJavaScriptEnabled,
                        onCheckedChange = {
                            onUpdateBrowserPrivacy(
                                settings.browserCosmeticFilteringEnabled,
                                settings.browserPopupBlockingEnabled,
                                settings.browserBlockThirdPartyCookies,
                                it
                            )
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = customCssSelector,
                            onValueChange = { customCssSelector = it },
                            label = { Text("Add Custom CSS Selector (e.g. .promo-box)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (customCssSelector.isNotBlank()) {
                                    onAddCustomCosmeticSelector(customCssSelector)
                                    customCssSelector = ""
                                    webViewRef.value?.reload()
                                }
                            }
                        ) {
                            Text("Add")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                        WebStorage.getInstance().deleteAllData()
                        webViewRef.value?.clearCache(true)
                        webViewRef.value?.clearHistory()
                        onShowFeedback("Cleared browser cookies, DOM storage, and cache.")
                        showBrowserShieldSheet = false
                    }
                ) {
                    Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Clear Cookies & Cache")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBrowserShieldSheet = false }) {
                    Text("Done")
                }
            }
        )
    }
}

@Composable
private fun BrowserSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun normalizeNavigationInput(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    ) {
        return trimmed
    }
    return if (trimmed.contains('.') && !trimmed.contains(' ')) {
        "https://$trimmed"
    } else {
        "https://duckduckgo.com/?q=${Uri.encode(trimmed)}"
    }
}
