package com.tradingapp.ui.positions

import android.os.Bundle; import android.view.*; import androidx.fragment.app.Fragment; import androidx.fragment.app.viewModels
import com.tradingapp.databinding.ScreenEquityPositionsBinding; import com.tradingapp.ui.adapter.RowAdapter; import com.tradingapp.ui.adapter.RowItem
import com.tradingapp.util.*; import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PositionsFragment : Fragment() {
    private var _b: ScreenEquityPositionsBinding? = null; private val b get() = _b!!
    private val vm: PositionsViewModel by viewModels()
    private lateinit var adapter: RowAdapter
    private val segment by lazy { arguments?.getString("segment") ?: "equity" }

    companion object { fun newInstance(seg: String) = PositionsFragment().apply { arguments = Bundle().also { it.putString("segment", seg) } } }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View { _b = ScreenEquityPositionsBinding.inflate(i, c, false); return b.root }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)
        adapter = RowAdapter { }
        vm.load(segment)
        launchOnStarted { vm.positions.collect { res ->
            if (res is Resource.Success) {
                b.tvSummaryValue.text = if (res.data.summary.dayPnl >= 0) "+${res.data.summary.dayPnl.toRupee()}" else res.data.summary.dayPnl.toRupee()
                adapter.submitList(res.data.data.map { p ->
                    RowItem(p.symbol.take(2), "${p.symbol} ${p.product}", "${p.quantity} qty · avg Rs.%.2f".format(p.avgCost),
                        p.lastPrice.toRupee(), "${if (p.isProfit) "+" else ""}${p.pnl.toRupee()}", if (p.isLong) "BUY" else "SELL")
                })
            }
        }}
    }
    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
