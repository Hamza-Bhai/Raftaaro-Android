package io.github.hamzabhai.raftaaro.ui.signin

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.hamzabhai.raftaaro.core.Site
import io.github.hamzabhai.raftaaro.graph
import io.github.hamzabhai.raftaaro.ui.theme.RaftaaroTheme

/**
 * The site's own sign-in page in a WebView. Raftaaro never sees the password: on Done it only reads
 * the site's cookies and keeps them in a file on this phone for yt-dlp.
 */
class SignInActivity : ComponentActivity() {
    private lateinit var site: Site

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        site = intent.getStringExtra(EXTRA_SITE)?.let { runCatching { Site.valueOf(it) }.getOrNull() } ?: return finish()
        enableEdgeToEdge()
        setContent {
            RaftaaroTheme {
                Scaffold(topBar = {
                    TopAppBar(
                        title = { Text("Sign in to ${site.label}") },
                        navigationIcon = { IconButton(onClick = ::finish) { Icon(Icons.Filled.Close, contentDescription = "Cancel") } },
                        actions = { TextButton(onClick = ::done) { Text("Done") } }
                    )
                }) { pad -> AndroidView(::webView, Modifier.fillMaxSize().padding(pad)) }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun webView(ctx: Context) = WebView(ctx).apply {
        // AndroidView gives WRAP_CONTENT by default; a WebView sized to its content collapses pages
        // that use height: 100% (Instagram's login page draws nothing)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        // Google refuses sign-in in apps that announce an embedded WebView ("; wv")
        if (site == Site.YOUTUBE) settings.userAgentString = settings.userAgentString.replace("; wv", "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            // stay on the site's own pages (and its login provider); everything else is blocked
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !allowedHost(request.url)
        }
        loadUrl(site.loginUrl)
    }

    private fun allowedHost(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (uri.scheme != "https") return false
        val domains = site.cookieDomains + if (site == Site.YOUTUBE) listOf("youtube.com", "google.com", "gstatic.com") else emptyList()
        return domains.any { host == it || host.endsWith(".$it") }
    }

    private fun done() {
        val cm = CookieManager.getInstance()
        cm.flush()
        SignIns.save(graph, site, site.cookieDomains.associateWith { d -> SignIns.cookiesOf(cm, d) })
        val ok = SignIns.isSignedIn(graph, site)
        Toast.makeText(this, if (ok) "Signed in to ${site.label}" else "No sign-in found — finish signing in first", Toast.LENGTH_SHORT).show()
        if (ok) finish()
    }

    companion object {
        private const val EXTRA_SITE = "site"
        fun intent(ctx: Context, site: Site): Intent = Intent(ctx, SignInActivity::class.java).putExtra(EXTRA_SITE, site.name)
    }
}
