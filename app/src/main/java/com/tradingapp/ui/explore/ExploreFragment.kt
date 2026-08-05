package com.tradingapp.ui.explore

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.tradingapp.data.model.Quote
import com.tradingapp.databinding.ScreenEquityExploreBinding
import com.tradingapp.ui.adapter.StockCardAdapter
import com.tradingapp.ui.chart.ChartActivity
import com.tradingapp.util.EXTRA_EXCHANGE
import com.tradingapp.util.EXTRA_NAME
import com.tradingapp.util.EXTRA_SYMBOL
import com.tradingapp.util.launchOnStarted
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ExploreFragment : Fragment() {

    private var _binding: ScreenEquityExploreBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ExploreViewModel by viewModels()
    private lateinit var cardAdapter: StockCardAdapter

    private val segment by lazy { arguments?.getString("segment") ?: "equity" }
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
        observeQuotes()
        observeSyncState()
        loadData()
    }

    private fun setupCardGrid() {
        cardAdapter = StockCardAdapter { quote -> openChart(quote) }
        binding.rvCards.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvCards.adapter = cardAdapter
        binding.rvCards.setHasFixedSize(true)
    }

    private fun loadData() = viewModel.load(segment, chipType)

    // The grid always has content — bundled universe + Room cache — so
    // this never toggles visibility, it just submits whatever's current.
    private fun observeQuotes() = launchOnStarted {
        viewModel.quotes.collect { list -> cardAdapter.submitList(list) }
    }

    // Small banner ABOVE the grid — never replaces or hides it.
    private fun observeSyncState() = launchOnStarted {
        viewModel.syncError.collect { message ->
            if (message != null) {
                binding.tvError.visibility = View.VISIBLE
                binding.tvError.text = "Showing last known prices — sync failed. Tap to retry."
                binding.tvError.setOnClickListener { loadData() }
            } else {
                binding.tvError.visibility = View.GONE
            }
        }
    }

    private fun openChart(quote: Quote) {
        Intent(requireContext(), ChartActivity::class.java).also {
            it.putExtra(EXTRA_SYMBOL,   quote.symbol.substringAfter(":"))
            it.putExtra(EXTRA_EXCHANGE, quote.safeExchange)
            it.putExtra(EXTRA_NAME,     quote.safeName)
            startActivity(it)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
