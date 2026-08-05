package com.tradingapp.ui.chart

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import com.tradingapp.databinding.ScreenChartBinding
import com.tradingapp.util.*
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ChartActivity : AppCompatActivity() {

    private lateinit var binding: ScreenChartBinding

    private val symbol   by lazy { intent.getStringExtra(EXTRA_SYMBOL)   ?: "RELIANCE" }
    private val exchange by lazy { intent.getStringExtra(EXTRA_EXCHANGE) ?: "NSE" }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ScreenChartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Header
        binding.tvSymbol.text   = symbol
        binding.tvExchange.text = exchange
        binding.ivBack.setOnClickListener { finish() }

        // WebView — loads TradingView Advanced Chart widget
        with(binding.chartWebView.settings) {
            javaScriptEnabled    = true
            domStorageEnabled    = true
            useWideViewPort      = true
            loadWithOverviewMode = true
            mixedContentMode     = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
        }

        binding.chartWebView.webChromeClient = WebChromeClient()
        binding.chartWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                // Tell chart.html which stock to display
                view.evaluateJavascript("setSymbol('$exchange', '$symbol')", null)
            }
        }

        // Load from assets
        binding.chartWebView.loadUrl("file:///android_asset/chart.html")

        // Period buttons — change TradingView interval
        setupPeriodButtons()

        // Hide trade buttons since this is a chart-only app for users
        binding.btnBuy.visibility  = android.view.View.GONE
        binding.btnSell.visibility = android.view.View.GONE
    }

    private fun setupPeriodButtons() {
        // Map our period tags to TradingView intervals
        val periodToTvInterval = mapOf(
            "1D" to "5",     // 5-minute candles
            "1W" to "30",    // 30-minute candles
            "1M" to "D",     // daily
            "3M" to "D",     // daily
            "1Y" to "W",     // weekly
            "5Y" to "M"      // monthly
        )

        listOf("1D","1W","1M","3M","1Y","5Y").forEach { period ->
            binding.periodContainer.findViewWithTag<android.widget.TextView>(period)?.setOnClickListener {
                val tvInterval = periodToTvInterval[period] ?: "D"
                binding.chartWebView.evaluateJavascript("setInterval('$tvInterval')", null)
            }
        }
    }

    override fun onBackPressed() {
        if (binding.chartWebView.canGoBack()) binding.chartWebView.goBack()
        else super.onBackPressed()
    }
}
