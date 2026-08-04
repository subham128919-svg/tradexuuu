package com.tradingapp.ui.holdings

import android.content.Intent; import android.os.Bundle; import android.view.*; import androidx.fragment.app.Fragment; import androidx.fragment.app.viewModels
import com.tradingapp.data.model.Holding; import com.tradingapp.databinding.ScreenEquityHoldingsBinding
import com.tradingapp.ui.adapter.RowAdapter; import com.tradingapp.ui.adapter.RowItem; import com.tradingapp.ui.chart.ChartActivity
import com.tradingapp.util.*; import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class HoldingsFragment : Fragment() {
    private var _b: ScreenEquityHoldingsBinding? = null
    private val b get() = _b!!
    private val vm: HoldingsViewModel by viewModels()
    private lateinit var adapter: RowAdapter
    private val segment by lazy { arguments?.getString("segment") ?: "equity" }

    companion object { fun newInstance(seg: String) = HoldingsFragment().apply { arguments = Bundle().also { it.putString("segment", seg) } } }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View { _b = ScreenEquityHoldingsBinding.inflate(i, c, false); return b.root }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)
        adapter = RowAdapter { row ->
            startActivity(Intent(requireContext(), ChartActivity::class.java).also {
                it.putExtra(Constants.EXTRA_SYMBOL, row.symbol); it.putExtra(Constants.EXTRA_EXCHANGE, row.exchange)
            })
        }
        // b.rvHoldings.adapter = adapter
        vm.load(segment)
        launchOnStarted { vm.holdings.collect { res ->
            if (res is Resource.Success) {
                val items = res.data.data.map { h ->
                    RowItem(h.symbol.take(2), h.symbol, "${h.quantity} qty · avg Rs.%.2f".format(h.avgCost),
                        h.currentValue.toRupee(), "${if (h.isProfit) "+" else ""}%.2f%%".format(h.pnlPercent), symbol = h.symbol)
                }
                adapter.submitList(items)
                b.tvSummaryValue.text  = res.data.summary.totalValue.toRupee()
                b.tvSummaryDelta.text  = "+Rs.%.0f (%.2f%%) today".format(res.data.summary.totalPnl, res.data.summary.totalPnlPct)
                b.tvStat1Value.text    = res.data.summary.totalInvested.toRupee()
                b.tvStat2Value.text    = "+%.2f%%".format(res.data.summary.totalPnlPct)
            }
        }}
    }
    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
