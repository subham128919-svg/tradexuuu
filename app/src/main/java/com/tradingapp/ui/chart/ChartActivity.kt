package com.tradingapp.ui.chart

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import com.tradingapp.R
import android.webkit.*
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tradingapp.data.model.toChartJson
import com.tradingapp.databinding.ScreenChartBinding
import com.tradingapp.util.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ChartActivity : AppCompatActivity() {

    private lateinit var binding: ScreenChartBinding
    private val viewModel: ChartViewModel by viewModels()

    private val symbol   by lazy { intent.getStringExtra(EXTRA_SYMBOL)   ?: "RELIANCE" }
    private val exchange by lazy { intent.getStringExtra(EXTRA_EXCHANGE) ?: "NSE" }
    private val name     by lazy { intent.getStringExtra(EXTRA_NAME)     ?: symbol }

    private var chartReady   = false
    private var pendingJson  = ""
    private var currentPeriod = "1M"

    private val periods = listOf("1D", "1W", "1M", "3M", "1Y", "5Y")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ScreenChartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupHeader()
        setupWebView()
        setupPeriodButtons()

        // Load initial chart data from our backend (DB-first)
        viewModel.loadChart(symbol, exchange, currentPeriod)
        observeCandles()
        observeQuote()
        observeLiveTick()

        // Hide trade buttons — this is a data/chart app
        binding.btnBuy.visibility  = View.GONE
        binding.btnSell.visibility = View.GONE
    }

    private fun setupHeader() {
        binding.tvSymbol.text   = symbol
        binding.tvExchange.text = exchange
        binding.ivBack.setOnClickListener { finish() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(binding.chartWebView.settings) {
            javaScriptEnabled    = true
            domStorageEnabled    = true
            useWideViewPort      = true
            loadWithOverviewMode = true
            cacheMode            = WebSettings.LOAD_NO_CACHE
        }
        binding.chartWebView.webChromeClient = WebChromeClient()
        binding.chartWebView.addJavascriptInterface(object {
            @JavascriptInterface fun onChartReady() {
                chartReady = true
                if (pendingJson.isNotEmpty()) {
                    val json = pendingJson; pendingJson = ""
                    binding.chartWebView.post {
                        binding.chartWebView.evaluateJavascript("setCandles('$json')", null)
                    }
                }
            }
        }, "Android")
        binding.chartWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                chartReady = true
                if (pendingJson.isNotEmpty()) {
                    val json = pendingJson; pendingJson = ""
                    view.post { view.evaluateJavascript("setCandles('$json')", null) }
                }
            }
        }
        // chart.html uses our own backend candle data — no TradingView login needed
        binding.chartWebView.loadUrl("file:///android_asset/chart.html")
    }

    private fun setupPeriodButtons() {
        periods.forEach { period ->
            binding.periodContainer.findViewWithTag<TextView>(period)?.let { tv ->
                tv.setOnClickListener {
                    if (period == currentPeriod) return@setOnClickListener
                    currentPeriod = period
                    highlightPeriod(period)
                    // Show loading state in chart
                    binding.chartWebView.evaluateJavascript("showLoading()", null)
                    // Load candles for new period from backend
                    viewModel.loadCandles(symbol, exchange, period)
                }
            }
        }
        highlightPeriod(currentPeriod)
    }

    private fun highlightPeriod(active: String) {
        periods.forEach { p ->
            binding.periodContainer.findViewWithTag<TextView>(p)?.let { tv ->
                val isActive = p == active
                tv.setTextColor(if (isActive) Color.parseColor("#6D5EF8") else Color.parseColor("#6E7681"))
                tv.setTypeface(tv.typeface, if (isActive) Typeface.BOLD else Typeface.NORMAL)
                tv.setBackgroundResource(if (isActive) R.drawable.bg_chip_on else R.drawable.bg_chip_off)
            }
        }
    }

    private fun observeCandles() = lifecycleScope.launch {
        viewModel.candles.collectLatest { res ->
            when (res) {
                is Resource.Loading -> { /* chart shows its own loading indicator */ }
                is Resource.Success -> pushCandles(res.data.toChartJson())
                is Resource.Error   -> {
                    binding.chartWebView.evaluateJavascript(
                        "document.getElementById('loading').style.display='none';" +
                        "document.getElementById('error').style.display='block';" +
                        "document.getElementById('error').textContent='${res.message.take(80)}'", null)
                }
            }
        }
    }

    private fun pushCandles(json: String) {
        val safe = json.replace("'", "\'")
        if (chartReady) {
            binding.chartWebView.post {
                binding.chartWebView.evaluateJavascript("setCandles('$safe')", null)
            }
        } else {
            pendingJson = safe
        }
    }

    private fun observeQuote() = lifecycleScope.launch {
        viewModel.quote.collectLatest { q ->
            q ?: return@collectLatest
            binding.tvPrice.text = q.ltp.toRupee()
            binding.tvChange.setChange(q.changePct, "%")
            binding.tvOhlv.text =
                "O %.2f  H %.2f  L %.2f  Vol %,d".format(q.open, q.high, q.low, q.volume)
        }
    }

    private fun observeLiveTick() = lifecycleScope.launch {
        viewModel.liveTicks.collectLatest { tick ->
            if (tick.symbol != "$exchange:$symbol") return@collectLatest
            binding.tvPrice.text = tick.ltp.toRupee()
            binding.tvChange.setChange(tick.changePct, "%")
            val time = tick.ts / 1000
            binding.chartWebView.post {
                binding.chartWebView.evaluateJavascript("addTick($time, ${tick.ltp})", null)
            }
        }
    }

    override fun onBackPressed() {
        if (binding.chartWebView.canGoBack()) binding.chartWebView.goBack()
        else super.onBackPressed()
    }
}
