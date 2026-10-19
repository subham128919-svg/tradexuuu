package com.tradingapp.ui.holdings

import android.content.Intent
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.tradingapp.databinding.ScreenEquityHoldingsBinding
import com.tradingapp.ui.adapter.RowAdapter
import com.tradingapp.ui.adapter.RowItem
import com.tradingapp.ui.chart.ChartActivity
import com.tradingapp.util.*
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class HoldingsFragment : Fragment() {
    private var _b: ScreenEquityHoldingsBinding? = null
    private val b get() = _b!!
    private val vm: HoldingsViewModel by viewModels()
    private lateinit var adapter: RowAdapter
    private val segment by lazy { arguments?.getString("segment") ?: "equity" }

    companion object {
        fun newInstance(seg: String) = HoldingsFragment().apply {
            arguments = Bundle().also { it.putString("segment", seg) }
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = ScreenEquityHoldingsBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)
        adapter = RowAdapter { row ->
            startActivity(Intent(requireContext(), ChartActivity::class.java).also {
                it.putExtra(EXTRA_SYMBOL,   row.symbol)
                it.putExtra(EXTRA_EXCHANGE, row.exchange)
                it.putExtra("product", row.product)
            })
        }

        // FIX: adapter was created but never attached to rvHoldings
        b.rvHoldings.layoutManager = LinearLayoutManager(requireContext())
        b.rvHoldings.adapter = adapter

        launchOnResumed {
            vm.holdings.collect { res ->
                if(res is Resource.Loading) adapter.submitList(emptyList())
                if(res is Resource.Error) com.google.android.material.snackbar.Snackbar.make(b.root, res.message ?: "Holdings refresh failed", com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
                if (res is Resource.Success) {
                    val data = res.data
                    try { b.tvSummaryValue.text = data.summary.totalValue.toRupee() }      catch (_: Exception) {}
                    try { b.tvSummaryDelta.text = "${if(data.summary.totalPnl>=0) "+" else ""}Rs.%.0f (%.2f%%) unrealised".format(data.summary.totalPnl, data.summary.totalPnlPct.toDouble()) + if(data.data.any { it.priceStale }) " · saved prices" else "" } catch (_: Exception) {}
                    try { b.tvStat1Value.text   = data.summary.totalInvested.toRupee() }  catch (_: Exception) {}
                    try { b.tvStat2Value.text   = "${if(data.summary.totalPnlPct>=0) "+" else ""}%.2f%%".format(data.summary.totalPnlPct.toDouble()) } catch (_: Exception) {}
                    if(data.data.any { !it.priceAvailable }) { b.tvSummaryValue.text = "—"; b.tvSummaryDelta.text = "— · prices unavailable"; b.tvStat2Value.text = "—" }

                    adapter.submitList(data.data.map { h ->
                        RowItem(
                            logo    = h.symbol.take(2),
                            name    = h.symbol,
                            sub     = "${h.quantity} qty · avg ${h.avgCost.toRupee()}",
                            right   = if(h.priceAvailable) h.currentValue.toRupee() else "—",
                            rightSub = if(!h.priceAvailable) "—" else "${if (h.isProfit) "+" else ""}%.2f%%".format(h.pnlPercent) + if(h.priceStale) " · saved" else "",
                            symbol  = h.symbol,
                            exchange = h.exchange,
                            product = h.product,
                            id = "${h.exchange}:${h.symbol}:${h.product}"
                        )
                    })
                }
            }
        }
    }

    override fun onResume() { super.onResume(); vm.load(segment) }
    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
