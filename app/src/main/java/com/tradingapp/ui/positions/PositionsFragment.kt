package com.tradingapp.ui.positions

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tradingapp.R
import com.tradingapp.data.api.AppPosition
import com.tradingapp.databinding.ScreenEquityPositionsBinding
import com.tradingapp.ui.chart.ChartActivity
import com.tradingapp.ui.order.OrderActivity
import com.tradingapp.util.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest

@AndroidEntryPoint
class PositionsFragment : Fragment() {

    private var _b: ScreenEquityPositionsBinding? = null
    private val b get() = _b!!
    private val vm: PositionsViewModel by viewModels()
    private lateinit var adapter: PositionsAdapter
    private var currentPositions: List<AppPosition> = emptyList()
    private val segment by lazy { arguments?.getString("segment") ?: "equity" }

    companion object {
        fun newInstance(seg: String) = PositionsFragment().apply {
            arguments = Bundle().also { it.putString("segment", seg) }
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = ScreenEquityPositionsBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)

        adapter = PositionsAdapter(
            onExit  = { pos -> openExitOrder(pos) },
            onClick = { pos ->
                val parts = pos.symbol.split(":")
                startActivity(Intent(requireContext(), ChartActivity::class.java).also {
                    it.putExtra(EXTRA_SYMBOL,   if (parts.size > 1) parts[1] else pos.symbol)
                    it.putExtra(EXTRA_EXCHANGE, if (parts.size > 1) parts[0] else "NSE")
                    it.putExtra(EXTRA_NAME,     pos.name)
                    it.putExtra("product",      pos.product)
                })
            }
        )
        b.rvPositions.layoutManager = LinearLayoutManager(requireContext())
        b.rvPositions.adapter = adapter

        // FIX #6: Observe positions — now shows real P&L with candle fallback
        launchOnResumed {
            vm.error.collectLatest { if (it != null) Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() }
        }
        launchOnResumed {
            vm.positions.collectLatest { resp ->
                if(resp == null) { currentPositions = emptyList(); adapter.submitList(emptyList()); return@collectLatest }
                currentPositions = resp.data
                if(resp.data.any { !it.priceAvailable }) b.tvSummaryValue.text = "— · prices unavailable" else updateSummary(resp.totalPnl, resp.totalPnlPct, resp.data.any { it.priceStale })
                adapter.submitList(resp.data.toMutableList())
            }
        }

        // Update prices in real-time from WebSocket ticks
        launchOnResumed {
            vm.liveTicks.collectLatest { tick ->
                val updated = currentPositions.map { p ->
                    if (p.symbol != tick.symbol || tick.ts <= 0 || tick.ts < p.priceUpdatedAt || tick.ts > System.currentTimeMillis() + 5000) return@map p
                    val ltp  = tick.ltp
                    if (ltp <= 0) return@map p
                    val pnl  = (ltp - p.avgPrice) * p.qty
                    val pnlP = if (p.invested > 0) pnl / p.invested * 100 else 0.0
                    p.copy(ltp = ltp, currentValue = ltp * kotlin.math.abs(p.qty.toLong()),
                           pnl = pnl, pnlPct = pnlP, isProfit = pnl >= 0,
                           priceAvailable = true, priceUpdatedAt = tick.ts,
                           priceStale = tick.ts <= 0 || System.currentTimeMillis() - tick.ts > 15000)
                }
                if (updated != currentPositions) {
                    currentPositions = updated
                    val totalPnl = updated.sumOf { it.pnl }
                    val totalInv = updated.sumOf { it.invested }
                    if(updated.any { !it.priceAvailable }) b.tvSummaryValue.text = "— · prices unavailable" else updateSummary(totalPnl, if (totalInv > 0) totalPnl / totalInv * 100 else 0.0, updated.any { it.priceStale })
                    adapter.submitList(updated.toMutableList())
                }
            }
        }
    }

    // FIX #7: Exit dialog — choose qty, market or limit
    private fun openExitOrder(pos: AppPosition) {
        val ltp = if (pos.ltp > 0) pos.ltp else pos.avgPrice
        startActivity(Intent(requireContext(), OrderActivity::class.java).also {
            it.putExtra(EXTRA_SYMBOL,   pos.symbol.substringAfter(":"))
            it.putExtra(EXTRA_EXCHANGE, pos.exchange)
            it.putExtra(EXTRA_NAME,     pos.name)
            it.putExtra("orderType",    OrderQuantity.exitSide(pos.qty))
            it.putExtra("product",      pos.product)
            it.putExtra("reduceOnly",   true)
            it.putExtra("positionId",   pos.id)
            it.putExtra(EXTRA_LOT_SIZE,  pos.lotSize ?: 1)
            it.putExtra("ltp",          ltp)
            it.putExtra("defaultQty",   kotlin.math.abs(pos.qty.toLong()).toString())
        })
    }

    private fun updateSummary(totalPnl: Double, totalPnlPct: Double, saved: Boolean = false) {
        try {
            val sign  = if (totalPnl >= 0) "+" else ""
            val color = if (totalPnl >= 0) Color.parseColor("#2FBF71") else Color.parseColor("#FF5C5C")
            // REMOVE: b.tvSummaryLabel.text = "Day P&L"   ← delete this line
            b.tvSummaryValue.text = "$sign₹%.2f ($sign%.2f%%)".format(totalPnl, totalPnlPct) + if(saved) " · saved prices" else ""
            b.tvSummaryValue.setTextColor(color)
        } catch (_: Exception) {}
    }

    override fun onResume() { super.onResume(); vm.start(segment) }
    override fun onPause() { vm.stop(); super.onPause() }
    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
