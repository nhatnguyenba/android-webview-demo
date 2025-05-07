package com.nhatnguyenba.webview

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ChromeLikeBrowser()
            }
        }
    }
}

@Composable
fun ChromeLikeBrowser() {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    // Browser states
    var currentUrl by remember { mutableStateOf("https://www.google.com") }
    var displayUrl by remember { mutableStateOf(currentUrl) }
    var pageTitle by remember { mutableStateOf("New Tab") }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var loadingProgress by remember { mutableFloatStateOf(0f) }
    var showSearchSuggestions by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentDownloadJob by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var hasSearched by remember { mutableStateOf(false) }

    // WebView reference
    var webView by remember { mutableStateOf<WebView?>(null) }

    // Download handling
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.data?.let { uri ->
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    currentDownloadJob?.let { (url, mimeType, fileName) ->
                        val client = OkHttpClient()
                        val request = Request.Builder()
                            .url(url)
                            .addHeader("Cookie", CookieManager.getInstance().getCookie(url))
                            .build()

                        client.newCall(request).execute().use { response ->
                            response.body?.byteStream()?.use { inputStream ->
                                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Đã tải xuống: $fileName")
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("Lỗi tải xuống: ${e.message ?: "Unknown error"}")
                    }
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (hasSearched) {
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(8.dp)
                ) {
                    // Thanh địa chỉ
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Navigation controls
                        IconButton(
                            onClick = { webView?.goBack() },
                            enabled = canGoBack,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }

                        IconButton(
                            onClick = { webView?.goForward() },
                            enabled = canGoForward,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Forward"
                            )
                        }

                        // URL/Search field
                        CustomSearchTextField(
                            value = displayUrl,
                            onValueChange = {
                                displayUrl = it
                                searchQuery = it
                                showSearchSuggestions = true
                            },
                            modifier = Modifier
                                .weight(1f),
                            onSearch = {
                                keyboardController?.hide()
                                currentUrl = processInputUrl(searchQuery)
                                webView?.loadUrl(currentUrl)
                                showSearchSuggestions = false
                            }
                        )

                        // Refresh/Stop button
                        IconButton(
                            onClick = {
                                if (isLoading) {
                                    webView?.stopLoading()
                                } else {
                                    webView?.reload()
                                }
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = if (isLoading) "Stop" else "Refresh",
                                tint = if (isLoading) Color.Red else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Progress bar
                    if (isLoading) {
                        LinearProgressIndicator(
                            progress = { loadingProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            // WebView
            ChromeWebView(
                url = currentUrl,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onTitleChanged = { title ->
                    pageTitle = title
                    displayUrl = currentUrl
                },
                onPageFinished = { url ->
                    // Phát hiện tìm kiếm Google
                    if (url?.contains("/search?q=") == true) {
                        hasSearched = true
                    }
                    displayUrl = url ?: ""
                },
                onUrlChanged = { url ->
                    currentUrl = url
                    displayUrl = url
                },
                onLoadingStateChanged = { loading -> isLoading = loading },
                onProgressChanged = { progress -> loadingProgress = progress },
                onNavigationStateChanged = { back, forward ->
                    canGoBack = back
                    canGoForward = forward
                },
                onError = { error ->
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(error)
                    }
                },
                onDownloadRequest = { url, contentDisposition, mimeType ->
                    Log.d("ChromeLikeBrowser", "onDownloadRequest mimeType: $mimeType")
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    currentDownloadJob = Triple(url, mimeType, fileName)

                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = mimeType
                        putExtra(Intent.EXTRA_TITLE, fileName)
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(mimeType))
                    }.let { intent ->
                        downloadLauncher.launch(intent)
                    }
                },
                onWebViewCreated = { view -> webView = view }
            )

            // Search suggestions
            if (showSearchSuggestions) {
                SearchSuggestions(
                    query = searchQuery,
                    onSearch = { query ->
                        currentUrl = processInputUrl(query)
                        webView?.loadUrl(currentUrl)
                        showSearchSuggestions = false
                    },
                    onDismiss = { showSearchSuggestions = false }
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChromeWebView(
    url: String,
    modifier: Modifier = Modifier,
    onTitleChanged: (String) -> Unit = {},
    onPageFinished: (String?) -> Unit = {},
    onUrlChanged: (String) -> Unit = {},
    onLoadingStateChanged: (Boolean) -> Unit = {},
    onProgressChanged: (Float) -> Unit = {},
    onNavigationStateChanged: (Boolean, Boolean) -> Unit = { _, _ -> },
    onError: (String) -> Unit = {},
    onWebViewCreated: (WebView) -> Unit = {},
    onDownloadRequest: (url: String, contentDisposition: String, mimeType: String) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    javaScriptCanOpenWindowsAutomatically = true
                    setSupportMultipleWindows(true)
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
//                        onNavigationStateChanged(canGoBack(), canGoForward())
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        request?.url?.let { uri ->
                            when {
                                isSpecialScheme(uri.scheme) -> {
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                        return true
                                    } catch (e: Exception) {
                                        onError("Không thể mở liên kết: $uri")
                                    }
                                }

                                else -> {
                                    onUrlChanged(uri.toString())
                                    return false
                                }
                            }
                        }
                        return false
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        onLoadingStateChanged(false)
                        onTitleChanged(view?.title ?: "")
                        onUrlChanged(url ?: "")
                        onNavigationStateChanged(canGoBack(), canGoForward())
                        onPageFinished(url)
                    }

                    override fun doUpdateVisitedHistory(
                        view: WebView?,
                        url: String?,
                        isReload: Boolean
                    ) {
                        super.doUpdateVisitedHistory(view, url, isReload)
//                        onNavigationStateChanged(
//                            view?.canGoBack() ?: false,
//                            view?.canGoForward() ?: false
//                        )
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        onProgressChanged(newProgress / 100f)
//                        onNavigationStateChanged(canGoBack(), canGoForward())
                    }

                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        onTitleChanged(title ?: "")
                    }
                }

                setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == MotionEvent.ACTION_UP) {
                        if (canGoBack()) {
                            goBack()
                            onNavigationStateChanged(canGoBack(), canGoForward())
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }

                // Xử lý download
                setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                    onDownloadRequest(url, contentDisposition, mimeType)
                }

                loadUrl(url)
                onWebViewCreated(this)
            }
        },
        modifier = modifier,
        update = { view ->
            if (view.url != url) {
//                view.loadUrl(url)
//                onNavigationStateChanged(view.canGoBack(), view.canGoForward())
            }
        }
    )
}

@Composable
fun SearchSuggestions(
    query: String,
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val suggestions = remember { generateSearchSuggestions(query) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clip(RoundedCornerShape(8.dp))
    ) {
        Column {
            suggestions.forEach { suggestion ->
                Text(
                    text = suggestion,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .clickable {
                            onSearch(suggestion)
                            onDismiss()
                        }
                )
            }
        }
    }
}

private fun generateSearchSuggestions(query: String): List<String> {
    return if (query.isNotEmpty()) {
        listOf(
            "$query - Tìm trên Google",
            "https://www.google.com/search?q=$query",
            "https://www.bing.com/search?q=$query",
            "https://duckduckgo.com/?q=$query"
        )
    } else {
        emptyList()
    }
}

private fun processInputUrl(input: String): String {
    return when {
        input.matches(Regex("^https?://.*")) -> input
        input.contains(" ") -> "https://www.google.com/search?q=${input.trim().replace(" ", "+")}"
        input.contains(".") -> "http://$input"
        else -> "https://www.google.com/search?q=$input"
    }
}

private fun isSpecialScheme(scheme: String?): Boolean {
    return when (scheme) {
        "http", "https" -> false
        else -> true
    }
}

@Composable
fun CustomSearchTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = {}
) {

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .padding(8.dp),
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(
                    onClick = { onValueChange("") }
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(40.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Search
        ),
        keyboardActions = KeyboardActions(
            onSearch = { onSearch() }
        )
    )
}

// Preview với cả light và dark theme
@Preview(showBackground = true)
@Composable
fun CustomSearchTextFieldPreview_Light() {
    MaterialTheme {
        CustomSearchTextField(
            value = "",
            onValueChange = {}
        )
    }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
fun CustomSearchTextFieldPreview_Dark() {
    MaterialTheme {
        CustomSearchTextField(
            value = "Sample Text",
            onValueChange = {}
        )
    }
}