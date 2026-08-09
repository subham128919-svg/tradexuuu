package com.tradingapp.ui.chart

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.tradingapp.R
import com.tradingapp.data.model.toChartJson
import com.tradingapp.databinding.ScreenChartBinding
import com.tradingapp.ui.order.OrderActivity
import com.tradingapp.util.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ChartActivity : AppCompatActivity() {

    private lateinit var b: ScreenChartBinding
    private val vm: ChartViewModel by viewModels()

    private val symbol   by lazy { intent.getStringExtra(EXTRA_SYMBOL)   ?: "RELIANCE" }
    private val exchange by lazy { intent.getStringExtra(EXTRA_EXCHANGE) ?: "NSE" }
    private val name     by lazy { intent.getStringExtra(EXTRA_NAME)     ?: symbol }

    private var chartReady   = false
    private var pendingJson  = ""
    private var currentPeriod = "1M"
    private val periods = listOf("1D","1W","1M","3M","6M","1Y","5Y")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ScreenChartBinding.inflate(layoutInflater)
        setContentView(b.root)

        // FIX #10: edge-to-edge — requires chartTopBar/chartBottomBar ids;
        // see screen_chart_topbar_patch.txt for the 2-line XML edit needed.
        // If those ids don't exist yet in your layout, this call safely
        // no-ops (findViewById-based binding lookups return null-safe).
        runCatching {
            applyEdgeToEdge(topView = b.root.findViewById(R.id.chartTopBar),
                             bottomView = b.root.findViewById(R.id.chartBottomBar))
        }
        WindowInsetsControllerCompat(window, b.root).isAppearanceLightStatusBars = false

        setupHeader()
        setupWebView()
        setupPeriodButtons()
        setupOrderButtons()

        vm.trackSymbol("$exchange:$symbol")
        vm.loadChart(symbol, exchange, currentPeriod)
        observeCandles()
        observeQuote()
        observeLiveTick()
    }

    private fun setupHeader() {
        b.tvLogo.text     = symbol.take(4).lowercase()
        b.tvSymbol.text   = symbol
        b.tvExchange.text = exchange
        b.tvName.text     = name
        b.ivBack.setOnClickListener { finish() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(b.chartWebView.settings) {
            javaScriptEnabled    = true
            domStorageEnabled    = true
            useWideViewPort      = true
            loadWithOverviewMode = true
            cacheMode            = WebSettings.LOAD_NO_CACHE
        }
        b.chartWebView.webChromeClient = WebChromeClient()
        b.chartWebView.webViewClient   = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                chartReady = true
                if (pendingJson.isNotEmpty()) {
                    val json = pendingJson; pendingJson = ""
                    view.post { view.evaluateJavascript("setCandles('$json')", null) }
                }
            }
        }
        b.chartWebView.loadUrl("file:///android_asset/chart.html")
    }

    private fun setupPeriodButtons() {
        periods.forEach { period ->
            b.periodContainer.findViewWithTag<TextView>(period)?.setOnClickListener {
                if (period == currentPeriod) return@setOnClickListener
                currentPeriod = period
                highlightPeriod(period)
                b.chartWebView.evaluateJavascript("showLoading()", null)
                vm.loadCandles(symbol, exchange, period)
            }
        }
        highlightPeriod(currentPeriod)
    }

    private fun highlightPeriod(active: String) {
        periods.forEach { p ->
            b.periodContainer.findViewWithTag<TextView>(p)?.let { tv ->
                val on = p == active
                tv.setTextColor(if (on) Color.parseColor("#6D5EF8") else Color.parseColor("#6E7681"))
                tv.setTypeface(tv.typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
                tv.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip_off)
            }
        }
    }

    private fun setupOrderButtons() {
        b.btnSip.visibility = View.GONE
        b.btnBuy.setOnClickListener  { openOrder("BUY")  }
        b.btnSell.setOnClickListener { openOrder("SELL") }
    }

    private fun openOrder(type: String) {
        val ltp = vm.quote.value?.ltp ?: 0.0
        startActivity(Intent(this, OrderActivity::class.java).apply {
            putExtra(EXTRA_SYMBOL,   symbol)
            putExtra(EXTRA_EXCHANGE, exchange)
            putExtra(EXTRA_NAME,     name)
            putExtra("orderType",    type)
            putExtra("ltp",          ltp)
        })
    }

    private fun observeCandles() = lifecycleScope.launch {
        vm.candles.collectLatest { res ->
            when (res) {
                is Resource.Loading -> {}
                is Resource.Success -> pushCandles(res.data.toChartJson())
                is Resource.Error   -> b.chartWebView.post {
                    b.chartWebView.evaluateJavascript(
                        "document.getElementById('loading').style.display='none';" +
                        "document.getElementById('errBox').style.display='block';" +
                        "document.getElementById('errBox').textContent='${res.message?.take(60)}'", null)
                }
            }
        }
    }

    private fun pushCandles(json: String) {
        val safe = json.replace("\\", "\\\\").replace("'", "\\'")
        if (chartReady) {
            b.chartWebView.post { b.chartWebView.evaluateJavascript("setCandles('$safe')", null) }
        } else {
            pendingJson = safe
        }
    }

    private fun observeQuote() = lifecycleScope.launch {
        vm.quote.collectLatest { q ->
            q ?: return@collectLatest
            b.tvPrice.text = q.ltp.toRupee()
            b.tvChange.setChange(q.changePct, "%")
            b.tvOhlv.text = "O %.2f  H %.2f  L %.2f  Vol %,d"
                .format(q.open, q.high, q.low, q.volume)
        }
    }

    private fun observeLiveTick() = lifecycleScope.launch {
        vm.liveTicks.collectLatest { tick ->
            if (tick.symbol != "$exchange:$symbol") return@collectLatest
            b.tvPrice.text = tick.ltp.toRupee()
            b.tvChange.setChange(tick.changePct, "%")
            val time = tick.ts / 1000
            b.chartWebView.post {
                b.chartWebView.evaluateJavascript("addTick($time, ${tick.ltp})", null)
            }
        }
    }

    override fun onBackPressed() {
        if (b.chartWebView.canGoBack()) b.chartWebView.goBack() else super.onBackPressed()
    }
}
