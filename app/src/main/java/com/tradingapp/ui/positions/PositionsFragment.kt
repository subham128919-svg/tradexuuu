package com.tradingapp.ui.positions

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.tradingapp.data.api.AppPosition
import com.tradingapp.databinding.ScreenEquityPositionsBinding
import com.tradingapp.ui.adapter.RowAdapter
import com.tradingapp.ui.adapter.RowItem
import com.tradingapp.ui.chart.ChartActivity
import com.tradingapp.util.*
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PositionsFragment : Fragment() {
    private var _b: ScreenEquityPositionsBinding? = null
    private val b get() = _b!!
    private val vm: PositionsViewModel by viewModels()
    private lateinit var adapter: RowAdapter
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
        adapter = RowAdapter { row ->
            startActivity(Intent(requireContext(), ChartActivity::class.java).also {
                it.putExtra(EXTRA_SYMBOL, row.symbol); it.putExtra(EXTRA_EXCHANGE, row.exchange)
            })
        }
        b.rvPositions.layoutManager = LinearLayoutManager(requireContext())
        b.rvPositions.adapter = adapter

        launchOnStarted {
            vm.positions.collect { resp ->
                resp ?: return@collect
                currentPositions = resp.data
                updateUI(resp)
            }
        }

        // Update prices in real-time from WebSocket ticks
        launchOnStarted {
            vm.liveTicks.collect { tick ->
                val updated = currentPositions.map { p ->
                    if (p.symbol == tick.symbol) {
                        val ltp = tick.ltp
                        val pnl = (ltp - p.avgPrice) * p.qty
                        p.copy(ltp = ltp, currentValue = ltp * p.qty, pnl = pnl,
                               pnlPct = if (p.invested > 0) pnl / p.invested * 100 else 0.0,
                               isProfit = pnl >= 0)
                    } else p
                }
                currentPositions = updated
                val totalPnl = updated.sumOf { it.pnl }
                try {
                    b.tvSummaryValue.text = if (totalPnl >= 0) "+${totalPnl.toRupee()}" else totalPnl.toRupee()
                    b.tvSummaryValue.setTextColor(if (totalPnl >= 0) Color.parseColor("#2FBF71") else Color.parseColor("#FF5C5C"))
                } catch (_: Exception) {}
                adapter.submitList(updated.toRows())
            }
        }
    }

    private fun updateUI(resp: com.tradingapp.data.api.AppPositionsResponse) {
        try {
            b.tvSummaryValue.text = if (resp.totalPnl >= 0) "+${resp.totalPnl.toRupee()}" else resp.totalPnl.toRupee()
            b.tvSummaryValue.setTextColor(if (resp.totalPnl >= 0) Color.parseColor("#2FBF71") else Color.parseColor("#FF5C5C"))
        } catch (_: Exception) {}
        adapter.submitList(resp.data.toRows())
    }

    private fun List<AppPosition>.toRows() = map { p ->
        RowItem(
            logo    = p.symbol.substringAfter(":").take(2),
            name    = p.symbol.substringAfter(":"),
            sub     = "${p.qty} qty · avg ${p.avgPrice.toRupee()}",
            right   = p.ltp.toRupee(),
            rightSub = "${if (p.isProfit) "+" else ""}${p.pnl.toRupee()} (${if (p.isProfit) "+" else ""}${"%.2f".format(p.pnlPct)}%)",
            symbol  = p.symbol, exchange = p.exchange
        )
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
