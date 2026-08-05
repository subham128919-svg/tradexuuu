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
            })
        }

        // FIX: adapter was created but never attached to rvHoldings
        b.rvHoldings.layoutManager = LinearLayoutManager(requireContext())
        b.rvHoldings.adapter = adapter

        vm.load(segment)
        launchOnStarted {
            vm.holdings.collect { res ->
                if (res is Resource.Success) {
                    val data = res.data
                    try { b.tvSummaryValue.text = data.summary.totalValue.toRupee() }      catch (_: Exception) {}
                    try { b.tvSummaryDelta.text = "+Rs.%.0f (%.2f%%) today".format(data.summary.totalPnl, data.summary.totalPnlPct.toDouble()) } catch (_: Exception) {}
                    try { b.tvStat1Value.text   = data.summary.totalInvested.toRupee() }  catch (_: Exception) {}
                    try { b.tvStat2Value.text   = "+%.2f%%".format(data.summary.totalPnlPct.toDouble()) } catch (_: Exception) {}

                    adapter.submitList(data.data.map { h ->
                        RowItem(
                            logo    = h.symbol.take(2),
                            name    = h.symbol,
                            sub     = "${h.quantity} qty · avg ${h.avgCost.toRupee()}",
                            right   = h.currentValue.toRupee(),
                            rightSub = "${if (h.isProfit) "+" else ""}%.2f%%".format(h.pnlPercent),
                            symbol  = h.symbol,
                            exchange = h.exchange
                        )
                    })
                }
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
