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

    private fun setupCardGrid() {
        cardAdapter = StockCardAdapter { quote -> openChart(quote) }
        binding.rvCards.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvCards.adapter = cardAdapter
    }

    private fun loadData() = viewModel.load(segment, chipType)

    private fun observeExplore() = launchOnStarted {
        viewModel.exploreItems.collect { res ->
            when (res) {
                is Resource.Loading -> {
                    binding.tvError.visibility = View.GONE
                }
                is Resource.Success -> {
                    binding.tvError.visibility = View.GONE
                    cardAdapter.submitList(res.data)
                    if (res.data.isNotEmpty()) {
                        viewModel.subscribeSymbols(res.data.map { it.symbol })
                    }
                }
                is Resource.Error -> {
                    binding.tvError.visibility = View.VISIBLE
                    binding.tvError.text = "Couldn't load data: ${res.message}"
                    binding.tvError.setOnClickListener { loadData() }
                }
            }
        }
    }

    // FIX: use explicit Quote constructor instead of copy() — avoids NPE when
    // Gson has set nullable fields to null behind Kotlin's back
    private fun observeLiveTicks() = launchOnStarted {
        viewModel.liveTick.collect { tickMap ->
            if (tickMap.isEmpty()) return@collect
            val current = cardAdapter.currentList
            val updated = current.map { q ->
                val newLtp = tickMap[q.symbol]
                if (newLtp != null && newLtp != q.ltp) {
                    Quote(
                        symbol    = q.symbol,
                        exchange  = q.exchange ?: "NSE",
                        name      = q.name ?: "",
                        ltp       = newLtp,
                        open      = q.open,
                        high      = q.high,
                        low       = q.low,
                        close     = q.close,
                        change    = q.change,
                        changePct = q.changePct,
                        volume    = q.volume,
                        updatedAt = System.currentTimeMillis()
                    )
                } else q
            }
            cardAdapter.submitList(updated)
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

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
