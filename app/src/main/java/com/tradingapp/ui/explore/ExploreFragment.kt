package com.tradingapp.ui.explore

import android.content.Intent
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.tradingapp.data.api.MoverItem
import com.tradingapp.data.model.Quote
import com.tradingapp.databinding.ScreenEquityExploreBinding
import com.tradingapp.ui.adapter.StockCardAdapter
import com.tradingapp.ui.adapter.TopMoverAdapter
import com.tradingapp.ui.adapter.TopMoverItem
import com.tradingapp.ui.chart.ChartActivity
import com.tradingapp.util.*
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ExploreFragment : Fragment() {

    private var _binding: ScreenEquityExploreBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ExploreViewModel by viewModels()
    private lateinit var cardAdapter: StockCardAdapter
    private lateinit var gainersAdapter: TopMoverAdapter
    private lateinit var losersAdapter: TopMoverAdapter

    private val segment by lazy { arguments?.getString("segment") ?: "equity" }
    private var chipType = "trending"

    companion object {
        fun newInstance(segment: String) = ExploreFragment().apply {
            arguments = Bundle().also { it.putString("segment", segment) }
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = ScreenEquityExploreBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)
        setupMainGrid()
        setupMoverLists()
        setupChips()
        observeQuotes()
        observeSyncState()
        observeMovers()
        viewModel.load(segment, chipType)
    }

    private fun setupMainGrid() {
        cardAdapter = StockCardAdapter { quote -> openChart(quote) }
        binding.rvCards.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvCards.adapter = cardAdapter
        binding.rvCards.setHasFixedSize(false)
    }

    private fun setupMoverLists() {
        gainersAdapter = TopMoverAdapter { m -> openChartBySymbol(m.symbol) }
        losersAdapter  = TopMoverAdapter { m -> openChartBySymbol(m.symbol) }

        binding.rvGainers.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvGainers.adapter = gainersAdapter

        binding.rvLosers.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvLosers.adapter = losersAdapter
    }

    private fun setupChips() {
        binding.chipTrending.setOnClickListener { switchChip("trending") }
        binding.chipGainers.setOnClickListener  { switchChip("gainers")  }
        binding.chipLosers.setOnClickListener   { switchChip("losers")   }
    }

    private fun switchChip(type: String) {
        chipType = type
        val accent = 0xFF6D5EF8.toInt(); val dim = 0xFF6E7681.toInt()
        val bgOn = com.tradingapp.R.drawable.bg_chip_on; val bgOff = com.tradingapp.R.drawable.bg_chip_off
        listOf(binding.chipTrending to "trending", binding.chipGainers to "gainers", binding.chipLosers to "losers")
            .forEach { (tv, t) ->
                tv.setTextColor(if (t == type) accent else dim)
                tv.setBackgroundResource(if (t == type) bgOn else bgOff)
            }
        viewModel.load(segment, type)
    }

    private fun observeQuotes() = launchOnStarted {
        viewModel.quotes.collect { list -> cardAdapter.submitList(list) }
    }

    private fun observeSyncState() = launchOnStarted {
        viewModel.syncError.collect { msg ->
            binding.tvError.visibility = if (msg != null) View.VISIBLE else View.GONE
            binding.tvError.text = msg?.let { "Showing cached data — $it Tap to retry." } ?: ""
            binding.tvError.setOnClickListener { viewModel.retry(segment, chipType) }
        }
    }

    private fun observeMovers() {
        launchOnStarted {
            viewModel.gainers.collect { list ->
                binding.layoutGainers.visibility = if (list.isNotEmpty()) View.VISIBLE else View.GONE
                gainersAdapter.submitList(list.map { it.toItem() })
            }
        }
        launchOnStarted {
            viewModel.losers.collect { list ->
                binding.layoutLosers.visibility = if (list.isNotEmpty()) View.VISIBLE else View.GONE
                losersAdapter.submitList(list.map { it.toItem() })
            }
        }
    }

    private fun MoverItem.toItem() = TopMoverItem(
        symbol    = symbol,
        name      = name,
        ltp       = ltp,
        changePct = changePct,
        changeAbs = changeAbs
    )

    private fun openChart(quote: Quote) {
        openChartBySymbol(quote.symbol)
    }

    private fun openChartBySymbol(fullSymbol: String) {
        val parts = fullSymbol.split(":")
        val exch  = if (parts.size > 1) parts[0] else "NSE"
        val sym   = if (parts.size > 1) parts[1] else fullSymbol
        Intent(requireContext(), ChartActivity::class.java).also {
            it.putExtra(EXTRA_SYMBOL, sym)
            it.putExtra(EXTRA_EXCHANGE, exch)
            startActivity(it)
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
