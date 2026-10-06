package com.tradingapp.ui.optionchain

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tradingapp.R
import com.tradingapp.data.api.OptionChainResponse
import com.tradingapp.databinding.ActivityOptionChainBinding
import com.tradingapp.ui.chart.ChartActivity
import com.tradingapp.util.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

@AndroidEntryPoint
class OptionChainActivity : AppCompatActivity() {

    private lateinit var b: ActivityOptionChainBinding
    private val vm: OptionChainViewModel by viewModels()
    private lateinit var adapter: OptionChainAdapter

    private val underlying by lazy { intent.getStringExtra(EXTRA_UNDERLYING) ?: "NIFTY" }
    private val displayName by lazy { intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: underlying }
    private var currentExpiry = ""
    private var currentLotSize: Int? = null

    // Scroll to ATM only on the first render of an expiry. Without this
    // guard, every live-tick-driven re-render would yank the list back
    // to the money while the user is scrolling.
    private var hasScrolledToAtm = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityOptionChainBinding.inflate(layoutInflater)
        setContentView(b.root)

        runCatching { applyEdgeToEdge(topView = b.chainHeader) }
        WindowInsetsControllerCompat(window, b.root).isAppearanceLightStatusBars = false

        b.tvUnderlyingName.text = displayName
        b.ivBack.setOnClickListener { finish() }

        adapter = OptionChainAdapter { symbol, label -> openContractChart(symbol, label) }
        b.rvChain.layoutManager = LinearLayoutManager(this)
        b.rvChain.adapter = adapter

        observeChain()
        observeLiveTicks()
        observeExpiries()
        vm.loadExpiries(underlying)
    }

    private fun openContractChart(symbol: String, label: String) {
        startActivity(Intent(this, ChartActivity::class.java).apply {
            putExtra(EXTRA_SYMBOL, symbol)
            putExtra(EXTRA_EXCHANGE, "NFO")
            putExtra(EXTRA_NAME, label)
            putExtra(EXTRA_LOT_SIZE, currentLotSize ?: 1)
        })
    }

    private fun observeChain() = lifecycleScope.launch {
        vm.chain.collectLatest { res ->
            when (res) {
                is Resource.Loading -> {
                    b.progressBar.visibility = View.VISIBLE
                    b.tvEmpty.visibility = View.GONE
                }
                is Resource.Success -> {
                    b.progressBar.visibility = View.GONE
                    renderChain(res.data)
                }
                is Resource.Error -> {
                    b.progressBar.visibility = View.GONE
                    b.tvEmpty.visibility = View.VISIBLE
                    b.tvEmpty.text = res.message ?: "Could not load option chain"
                }
            }
        }
    }

    private fun renderChain(data: OptionChainResponse) {
        currentExpiry = data.expiry
        currentLotSize = data.lotSize
        adapter.underlyingLabel = displayName
        adapter.expiryLabel = data.expiry
        adapter.spot = data.spot

        // Strike nearest to spot, and its row index. Computed here from
        // the spot price the response already carries, so no API model
        // change is needed anywhere.
        val atmIndex = findAtmIndex(data)
        adapter.atmStrike = atmIndex.takeIf { it >= 0 }?.let { data.rows[it].strike }

        // submitList's callback runs after the diff is applied, so the
        // row at atmIndex genuinely exists by the time we scroll to it.
        adapter.submitList(data.rows) {
            if (!hasScrolledToAtm && atmIndex >= 0) {
                hasScrolledToAtm = true
                scrollToAtm(atmIndex)
            }
        }

        b.tvSpot.text = data.spot?.let { "₹%.2f".format(it) } ?: "—"
        b.tvLotSize.text = data.lotSize?.let { "Lot size: $it" } ?: ""
        b.tvEmpty.visibility = if (data.rows.isEmpty()) View.VISIBLE else View.GONE
        if (data.rows.isEmpty()) b.tvEmpty.text = "No contracts found for this expiry"

        highlightCurrentExpiryChip()
    }

    private fun findAtmIndex(data: OptionChainResponse): Int {
        val spot = data.spot ?: return -1
        if (data.rows.isEmpty()) return -1
        var best = Double.MAX_VALUE
        var idx = -1
        data.rows.forEachIndexed { i, row ->
            val d = abs(row.strike - spot)
            if (d < best) { best = d; idx = i }
        }
        return idx
    }

    /**
     * Puts the ATM strike a few rows below the top rather than flush
     * against it, so the in-the-money strikes above it are visible
     * without scrolling up — the way every broker's chain opens. Then
     * pulses the row twice so the eye lands on it.
     */
    private fun scrollToAtm(atmIndex: Int) {
        val lm = b.rvChain.layoutManager as? LinearLayoutManager ?: return
        lm.scrollToPositionWithOffset(maxOf(0, atmIndex - 3), 0)
        b.rvChain.post {
            val v = b.rvChain.findViewHolderForAdapterPosition(atmIndex)?.itemView ?: return@post
            v.alpha = 0.25f
            v.animate().alpha(1f).setDuration(260).withEndAction {
                v.animate().alpha(0.45f).setDuration(200).withEndAction {
                    v.animate().alpha(1f).setDuration(260).start()
                }.start()
            }.start()
        }
    }

    // Collected exactly once, for the Activity's lifetime — the expiry
    // LIST only changes on the initial load (switching expiries calls
    // loadChain(), not loadExpiries() again), so one collector here is
    // enough; re-subscribing per chain load would leak a growing number
    // of redundant coroutines over repeated expiry switches.
    private fun observeExpiries() = lifecycleScope.launch {
        vm.expiries.collectLatest { expiries -> buildExpiryChips(expiries) }
    }

    private fun buildExpiryChips(expiries: List<String>) {
        b.expiryContainer.removeAllViews()
        expiries.forEach { expiry ->
            val chip = TextView(this@OptionChainActivity).apply {
                text = formatExpiryShort(expiry)
                tag = expiry
                textSize = 13f
                setPadding(28, 16, 28, 16)
                setOnClickListener {
                    if (expiry == currentExpiry) return@setOnClickListener
                    // Re-centre on the new expiry's own ATM.
                    hasScrolledToAtm = false
                    vm.loadChain(underlying, expiry)
                }
            }
            val params = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = 8
            b.expiryContainer.addView(chip, params)
        }
        highlightCurrentExpiryChip()
    }

    // Just updates each chip's text color/background/weight based on
    // whether it matches currentExpiry — no view rebuilding needed.
    private fun highlightCurrentExpiryChip() {
        for (i in 0 until b.expiryContainer.childCount) {
            val chip = b.expiryContainer.getChildAt(i) as? TextView ?: continue
            val on = chip.tag == currentExpiry
            chip.setTextColor(Color.parseColor(if (on) "#6D5EF8" else "#6E7681"))
            chip.setTypeface(chip.typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
            chip.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip_off)
        }
    }

    // "2026-08-28" → "28 Aug"
    private fun formatExpiryShort(iso: String): String = try {
        val parts = iso.split("-")
        val months = listOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
        "${parts[2].toInt()} ${months[parts[1].toInt() - 1]}"
    } catch (_: Exception) { iso }

    private fun observeLiveTicks() = lifecycleScope.launch {
        vm.liveTicks.collectLatest { tick ->
            // tick.symbol arrives as "NFO:NIFTY24AUG24000CE" — strip the
            // exchange prefix to match the bare tradingsymbol used in
            // OptionContract.symbol.
            val bare = tick.symbol.substringAfter(":")
            adapter.updateTick(bare, tick.ltp, tick.changePct)
        }
    }
}