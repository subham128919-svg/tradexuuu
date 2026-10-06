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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.tradingapp.R
import com.tradingapp.data.model.toChartJson
import com.tradingapp.databinding.ScreenChartBinding
import com.tradingapp.ui.order.OrderActivity
import com.tradingapp.ui.optionchain.OptionChainActivity
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
    // F&O only: >1 when this chart was opened from the option chain for
    // a specific contract. Equity charts default to 1 (no lot concept).
    private val lotSize  by lazy { intent.getIntExtra(EXTRA_LOT_SIZE, 1) }

    private var chartReady   = false
    private var pendingJson  = ""
    private var currentPeriod = "1M"
    private val periods = listOf("1D","1W","1M","3M","6M","1Y","5Y")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ScreenChartBinding.inflate(layoutInflater)
        setContentView(b.root)

        configureScreenInsets()

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

    @Suppress("DEPRECATION")
    private fun configureScreenInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, b.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }
        val topPadding = b.chartTopBar.paddingTop
        val bottomPadding = b.chartBottomBar.paddingBottom
        val leftPadding = b.root.paddingLeft
        val rightPadding = b.root.paddingRight
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            b.chartTopBar.updatePadding(top = topPadding + bars.top)
            b.chartBottomBar.updatePadding(bottom = bottomPadding + bars.bottom)
            b.root.updatePadding(left = leftPadding + bars.left, right = rightPadding + bars.right)
            insets
        }
        ViewCompat.requestApplyInsets(b.root)
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
                val isActive = p == active
                tv.isSelected = isActive

                tv.setTextColor(
                    if (isActive) {
                        Color.WHITE
                    } else {
                        Color.parseColor("#364A62")
                    }
                )

                tv.setTypeface(
                    tv.typeface,
                    if (isActive) Typeface.BOLD else Typeface.NORMAL
                )

                tv.setBackgroundResource(
                    if (isActive) {
                        R.drawable.chart_modern_bg_period_active
                    } else {
                        R.drawable.chart_modern_bg_period
                    }
                )
            }
        }
    }
    private fun setupOrderButtons() {
        b.btnBuy.setOnClickListener  { openOrder("BUY")  }
        b.btnSell.setOnClickListener { openOrder("SELL") }
        b.btnOptionChain.setOnClickListener { openOptionChain() }
        vm.checkOptionsAvailable("$exchange:$symbol")
        lifecycleScope.launch {
            vm.hasOptions.collectLatest { has ->
                b.btnOptionChain.visibility = if (has) View.VISIBLE else View.GONE
            }
        }
    }

    // Kite's F&O "underlying" name doesn't always match the spot
    // trading symbol for indices (e.g. spot "NIFTY 50" → underlying
    // "NIFTY"). Mirror of optionChainService.js's SPOT_SYMBOL_MAP,
    // reversed.
    private fun toUnderlying(sym: String): String = when (sym.uppercase()) {
        "NIFTY 50"           -> "NIFTY"
        "NIFTY BANK"         -> "BANKNIFTY"
        "NIFTY FIN SERVICE"  -> "FINNIFTY"
        "NIFTY MIDCAP 100"   -> "MIDCPNIFTY"
        else                 -> sym.uppercase()
    }

    private fun openOptionChain() {
        startActivity(Intent(this, OptionChainActivity::class.java).apply {
            putExtra(EXTRA_UNDERLYING,   toUnderlying(symbol))
            putExtra(EXTRA_DISPLAY_NAME, name)
        })
    }

    private fun openOrder(type: String) {
        val ltp = vm.quote.value?.ltp ?: 0.0
        startActivity(Intent(this, OrderActivity::class.java).apply {
            putExtra(EXTRA_SYMBOL,   symbol)
            putExtra(EXTRA_EXCHANGE, exchange)
            putExtra(EXTRA_NAME,     name)
            putExtra("orderType",    type)
            putExtra("ltp",          ltp)
            putExtra(EXTRA_LOT_SIZE, lotSize)
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
            tintChange(q.changePct)
            b.tvOhlv.text = "O %.2f  H %.2f  L %.2f  Vol %,d"
                .format(q.open, q.high, q.low, q.volume)
        }
    }

    private fun observeLiveTick() = lifecycleScope.launch {
        vm.liveTicks.collectLatest { tick ->
            if (tick.symbol != "$exchange:$symbol") return@collectLatest
            b.tvPrice.text = tick.ltp.toRupee()
            b.tvChange.setChange(tick.changePct, "%")
            tintChange(tick.changePct)
            val time = tick.ts / 1000
            b.chartWebView.post {
                b.chartWebView.evaluateJavascript("addTick($time, ${tick.ltp})", null)
            }
        }
    }

    // Keep the existing percentage formatting, with colors for a light surface.
    private fun tintChange(value: Double) {
        b.tvChange.setTextColor(
            Color.parseColor(if (value >= 0) "#087D6B" else "#C34458")
        )
    }

    override fun onBackPressed() {
        if (b.chartWebView.canGoBack()) b.chartWebView.goBack() else super.onBackPressed()
    }
}
