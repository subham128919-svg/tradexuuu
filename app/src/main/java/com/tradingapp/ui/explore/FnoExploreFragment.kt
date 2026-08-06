package com.tradingapp.ui.explore

import android.content.Intent
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.tradingapp.databinding.ScreenFnoExploreBinding
import com.tradingapp.ui.adapter.RowAdapter
import com.tradingapp.ui.adapter.RowItem
import com.tradingapp.ui.chart.ChartActivity
import com.tradingapp.util.*
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class FnoExploreFragment : Fragment() {

    private var _b: ScreenFnoExploreBinding? = null
    private val b get() = _b!!
    private val vm: FnoExploreViewModel by viewModels()
    private lateinit var adapter: RowAdapter

    companion object {
        fun newInstance(segment: String) = FnoExploreFragment().apply {
            arguments = Bundle().also { it.putString("segment", segment) }
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = ScreenFnoExploreBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)
        adapter = RowAdapter { row ->
            val parts = row.symbol.split(":")
            startActivity(Intent(requireContext(), ChartActivity::class.java).also {
                it.putExtra(EXTRA_SYMBOL,   if (parts.size > 1) parts[1] else row.symbol)
                it.putExtra(EXTRA_EXCHANGE, if (parts.size > 1) parts[0] else "NSE")
                it.putExtra(EXTRA_NAME,     row.name)
            })
        }
        b.rvIndices.layoutManager = LinearLayoutManager(requireContext())
        b.rvIndices.adapter = adapter

        launchOnStarted {
            vm.indices.collect { list ->
                adapter.submitList(list.map { idx ->
                    RowItem(
                        logo     = idx.name.take(2).uppercase(),
                        name     = idx.name,
                        sub      = idx.symbol.substringBefore(":"),
                        right    = if (idx.ltp > 0) "%.2f".format(idx.ltp) else "—",
                        rightSub = "${if (idx.changePct >= 0) "+" else ""}%.2f%%".format(idx.changePct),
                        symbol   = idx.symbol,
                        exchange = idx.symbol.substringBefore(":", "NSE")
                    )
                })
                b.tvMarketStatus.text = if (list.isEmpty()) "Loading…" else "Top 10 Indices"
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
