package com.tradingapp.ui.chart

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.*
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tradingapp.R
import com.tradingapp.data.model.toChartJson
import com.tradingapp.databinding.ScreenChartBinding
import com.tradingapp.ui.trade.TradeActivity
import com.tradingapp.util.*        // ← fixed: package star import works; object star import does NOT
import com.tradingapp.util.Resource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ChartActivity : AppCompatActivity() {

    private lateinit var binding: ScreenChartBinding
    private val viewModel: ChartViewModel by viewModels()

    private val symbol   by lazy { intent.getStringExtra(EXTRA_SYMBOL)   ?: "" }
    private val exchange by lazy { intent.getStringExtra(EXTRA_EXCHANGE) ?: "NSE" }

    private var chartReady    = false
    private var pendingJson   = ""
    private var currentPeriod = "1M"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ScreenChartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupHeader()
        setupWebView()
        setupPeriodButtons()
        setupTradeButtons()
        observeCandles()
        observeQuote()
        observeLiveTick()
        viewModel.loadChart(symbol, exchange, currentPeriod)
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
            builtInZoomControls  = false
            displayZoomControls  = false
            cacheMode            = WebSettings.LOAD_NO_CACHE
        }
        binding.chartWebView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onChartReady() {
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
        binding.chartWebView.loadUrl("file:///android_asset/chart.html")
    }

    private fun setupPeriodButtons() {
        listOf("1D","1W","1M","3M","1Y","5Y").forEach { period ->
            binding.periodContainer.findViewWithTag<TextView>(period)?.setOnClickListener {
                if (period == currentPeriod) return@setOnClickListener
                currentPeriod = period
                updatePeriodHighlight(period)
                viewModel.loadChart(symbol, exchange, period)
            }
        }
        updatePeriodHighlight(currentPeriod)
    }

    private fun updatePeriodHighlight(active: String) {
        listOf("1D","1W","1M","3M","1Y","5Y").forEach { p ->
            binding.periodContainer.findViewWithTag<TextView>(p)?.let { tv ->
                tv.setTextColor(if (p == active) Color.parseColor("#6D5EF8") else Color.parseColor("#6E7681"))
                tv.setTypeface(tv.typeface, if (p == active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                tv.setBackgroundResource(if (p == active) R.drawable.bg_chip_on else R.drawable.bg_chip_off)
            }
        }
    }

    private fun setupTradeButtons() {
        binding.btnBuy.setOnClickListener  { openTrade("BUY") }
        binding.btnSell.setOnClickListener { openTrade("SELL") }
    }

    private fun openTrade(type: String) {
        startActivity(Intent(this, TradeActivity::class.java).also {
            it.putExtra(EXTRA_SYMBOL, symbol)
            it.putExtra(EXTRA_EXCHANGE, exchange)
            it.putExtra("transactionType", type)
        })
    }

    private fun observeCandles() = lifecycleScope.launch {
        viewModel.candles.collectLatest { res ->
            when (res) {
                is Resource.Loading -> { }
                is Resource.Success -> pushCandlesToChart(res.data.toChartJson())
                is Resource.Error   -> { }
            }
        }
    }

    private fun pushCandlesToChart(json: String) {
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
            binding.tvOhlv.text = "O %.2f  H %.2f  L %.2f  V %,d".format(q.open, q.high, q.low, q.volume)
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
}
