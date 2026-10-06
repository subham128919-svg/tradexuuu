package com.tradingapp.ui.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.tradingapp.databinding.ActivityWebviewBinding

/**
 * Feature #8 — opens "About TradeX" and "Charges & pricing" inside the
 * app. The URLs are not hard-coded: ProfileActivity fetches them from
 * /api/v1/settings/links, and the admin edits them at
 * /admin/manage.html → "App links".
 */
class WebViewActivity : AppCompatActivity() {

    private lateinit var b: ActivityWebviewBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(b.root)

        val url   = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "TradeX"

        b.tvTitle.text = title
        b.ivBack.setOnClickListener { finish() }

        if (url.isBlank()) { showError("This page has not been configured yet."); return }

        b.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        }

        b.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView?, u: String?, f: Bitmap?) {
                b.progress.visibility = View.VISIBLE
                b.tvError.visibility  = View.GONE
            }

            override fun onPageFinished(v: WebView?, u: String?) {
                b.progress.visibility = View.GONE
            }

            // Keep navigation inside the app
            override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                val target = req?.url?.toString() ?: return false
                return if (target.startsWith("http://") || target.startsWith("https://")) {
                    v?.loadUrl(target); true
                } else {
                    // mailto:, tel:, upi: etc. go to the system handler
                    try { startActivity(Intent(Intent.ACTION_VIEW, req.url)) } catch (_: Exception) { }
                    true
                }
            }

            override fun onReceivedError(v: WebView?, req: WebResourceRequest?, err: WebResourceError?) {
                if (req?.isForMainFrame == true) showError("Could not load this page. Check your connection.")
            }
        }

        b.btnRetry.setOnClickListener {
            b.tvError.visibility = View.GONE
            b.btnRetry.visibility = View.GONE
            b.webView.visibility = View.VISIBLE
            b.webView.reload()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (b.webView.canGoBack()) b.webView.goBack() else finish()
            }
        })

        b.webView.loadUrl(url)
    }

    private fun showError(msg: String) {
        b.progress.visibility = View.GONE
        b.webView.visibility  = View.GONE
        b.tvError.text        = msg
        b.tvError.visibility  = View.VISIBLE
        b.btnRetry.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        runCatching { b.webView.destroy() }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL   = "extra_url"
        const val EXTRA_TITLE = "extra_title"

        fun intent(ctx: Context, url: String?, title: String) =
            Intent(ctx, WebViewActivity::class.java)
                .putExtra(EXTRA_URL, url ?: "")
                .putExtra(EXTRA_TITLE, title)
    }
}
