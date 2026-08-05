package com.tradingapp.ui.watchlist

import android.content.Intent
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.tradingapp.databinding.ScreenEquityWatchlistBinding
import com.tradingapp.ui.adapter.RowAdapter
import com.tradingapp.ui.adapter.RowItem
import com.tradingapp.ui.chart.ChartActivity
import com.tradingapp.util.*
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class WatchlistFragment : Fragment() {
    private var _b: ScreenEquityWatchlistBinding? = null
    private val b get() = _b!!
    private val vm: WatchlistViewModel by viewModels()
    private lateinit var adapter: RowAdapter
    private val segment by lazy { arguments?.getString("segment") ?: "equity" }

    companion object {
        fun newInstance(seg: String) = WatchlistFragment().apply {
            arguments = Bundle().also { it.putString("segment", seg) }
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = ScreenEquityWatchlistBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)
        adapter = RowAdapter { row ->
            startActivity(Intent(requireContext(), ChartActivity::class.java).also {
                it.putExtra(EXTRA_SYMBOL, row.symbol)
                it.putExtra(EXTRA_EXCHANGE, row.exchange)
            })
        }

        // FIX: adapter was created but never attached to rvWatchlist
        b.rvWatchlist.layoutManager = LinearLayoutManager(requireContext())
        b.rvWatchlist.adapter = adapter

        vm.load()
        launchOnStarted {
            vm.watchlist.collect { items ->
                adapter.submitList(items.map { w ->
                    RowItem(
                        w.symbol.take(2), w.symbol, w.exchange,
                        "Rs.%.2f".format(w.ltp),
                        "${if (w.changePct >= 0) "+" else ""}%.2f%%".format(w.changePct),
                        symbol = w.symbol, exchange = w.exchange
                    )
                })
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
