package com.tradingapp.ui.explore

import android.content.Intent
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.tradingapp.data.model.Quote
import com.tradingapp.databinding.ScreenEquityExploreBinding
import com.tradingapp.ui.adapter.StockCardAdapter
import com.tradingapp.ui.chart.ChartActivity
import com.tradingapp.util.*
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ExploreFragment : Fragment() {

    private var _binding: ScreenEquityExploreBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ExploreViewModel by viewModels()
    private lateinit var cardAdapter: StockCardAdapter

    private val segment  by lazy { arguments?.getString("segment") ?: "equity" }
    private var chipType = "trending"

    companion object {
        fun newInstance(segment: String) = ExploreFragment().apply {
            arguments = Bundle().also { it.putString("segment", segment) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ScreenEquityExploreBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupCardGrid()
        observeExplore()
        observeLiveTicks()
        loadData()
    }

    // FIX: RecyclerView was created but never attached — nothing rendered, nothing clickable
    private fun setupCardGrid() {
        cardAdapter = StockCardAdapter { quote -> openChart(quote) }
        binding.rvCards.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvCards.adapter = cardAdapter
    }

    private fun loadData() = viewModel.load(segment, chipType)

    private fun observeExplore() = launchOnStarted {
        viewModel.exploreItems.collect { res ->
            when (res) {
                is Resource.Loading -> { }
                is Resource.Success -> {
                    cardAdapter.submitList(res.data)
                    viewModel.subscribeSymbols(res.data.map { it.symbol })
                }
                is Resource.Error -> { }
            }
        }
    }

    // FIX: live ticks were collected into state but never applied to the visible list
    private fun observeLiveTicks() = launchOnStarted {
        viewModel.liveTick.collect { tickMap ->
            if (tickMap.isEmpty()) return@collect
            val current = cardAdapter.currentList
            val updated = current.map { q ->
                val newLtp = tickMap[q.symbol]
                if (newLtp != null && newLtp != q.ltp) q.copy(ltp = newLtp) else q
            }
            cardAdapter.submitList(updated)
        }
    }

    private fun openChart(quote: Quote) {
        Intent(requireContext(), ChartActivity::class.java).also {
            it.putExtra(EXTRA_SYMBOL,   quote.symbol.substringAfter(":"))
            it.putExtra(EXTRA_EXCHANGE, quote.exchange)
            it.putExtra(EXTRA_NAME,     quote.name)
            startActivity(it)
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
