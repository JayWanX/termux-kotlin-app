package com.termux.app.activities

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.app.ui.compose.theme.TermuxTheme
import com.termux.shared.termux.TermuxConstants

/**
 * Embedded browser for the Termux wiki.
 *
 * Keeps the original wiki browsing behaviour that used to live in
 * [HelpActivity], now reachable from the command documentation screen.
 */
class WikiActivity : ComponentActivity() {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val host = this

        setContent {
            TermuxTheme {
                WikiScreen(
                    onBack = {
                        val view = webView
                        if (view != null && view.canGoBack()) {
                            view.goBack()
                        } else {
                            host.finish()
                        }
                    },
                    host = host
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val view = webView
        if (view != null && view.canGoBack()) {
            view.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WikiScreen(
    onBack: () -> Unit,
    host: ComponentActivity
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Termux Wiki", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        AndroidView(
            factory = { context -> createContainer(context, host) },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}

private fun createContainer(context: Context, host: ComponentActivity): FrameLayout {
    val container = FrameLayout(context)
    container.layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    val progress = ProgressBar(context)
    progress.layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER
    )
    progress.isIndeterminate = true
    container.addView(progress)

    val web = WebView(context)
    web.settings.cacheMode = WebSettings.LOAD_NO_CACHE
    web.clearCache(true)
    web.layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
        Gravity.CENTER
    )

    web.webViewClient = object : WebViewClient() {
        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            if (url == TermuxConstants.TERMUX_WIKI_URL ||
                url.startsWith(TermuxConstants.TERMUX_WIKI_URL + "/")
            ) {
                return false
            }
            try {
                host.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return true
            } catch (e: ActivityNotFoundException) {
                return false
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            progress.visibility = View.GONE
        }
    }

    container.addView(web)
    web.loadUrl(TermuxConstants.TERMUX_WIKI_URL)

    return container
}
