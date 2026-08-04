package com.tradingapp.ui.explore

import android.content.Intent; import android.os.Bundle; import android.view.*; import android.widget.TextView; import androidx.fragment.app.Fragment; import androidx.fragment.app.viewModels
import com.google.android.material.chip.Chip; import com.tradingapp.R; import com.tradingapp.data.model.Quote
import com.tradingapp.databinding.ScreenEquityExploreBinding; import com.tradingapp.ui.adapter.StockCardAdapter
import com.tradingapp.ui.chart.ChartActivity; import com.tradingapp.util.*; import dagger.hilt.android.AndroidEntryPoint

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
        loadData()
    }

    private fun setupCardGrid() {
        cardAdapter = StockCardAdapter { quote -> openChart(quote) }
        // Use RecyclerView with GridLayoutManager(2) in the fragment layout
        // binding.rvCards.layoutManager = GridLayoutManager(requireContext(), 2)
        // binding.rvCards.adapter = cardAdapter
    }

    private fun loadData() = viewModel.load(segment, chipType)

    private fun observeExplore() = launchOnStarted {
        viewModel.exploreItems.collect { res ->
            when (res) {
                is Resource.Loading -> { /* show shimmer */ }
                is Resource.Success -> {
                    cardAdapter.submitList(res.data)
                    viewModel.subscribeSymbols(res.data.map { it.symbol })
                }
                is Resource.Error -> { /* show error */ }
            }
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
