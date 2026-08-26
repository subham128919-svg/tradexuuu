package com.tradingapp.ui.explore

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.tradingapp.databinding.ScreenFnoExploreBinding
import com.tradingapp.ui.chart.ChartActivity
import com.tradingapp.util.EXTRA_EXCHANGE
import com.tradingapp.util.EXTRA_NAME
import com.tradingapp.util.EXTRA_SYMBOL
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class FnoExploreFragment : Fragment() {

    private var _binding: ScreenFnoExploreBinding? = null
    private val binding: ScreenFnoExploreBinding
        get() = _binding!!

    private val viewModel: FnoExploreViewModel by viewModels()

    private lateinit var adapter: FnoIndexAdapter

    companion object {
        private const val ARG_SEGMENT = "segment"

        fun newInstance(segment: String): FnoExploreFragment {
            return FnoExploreFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SEGMENT, segment)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ScreenFnoExploreBinding.inflate(
            inflater,
            container,
            false
        )
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        adapter = FnoIndexAdapter { item ->

            val parts = item.symbol.split(":")

            val exchange =
                if (parts.size > 1) parts[0] else "NSE"

            val symbol =
                if (parts.size > 1) parts[1] else item.symbol

            startActivity(
                Intent(
                    requireContext(),
                    ChartActivity::class.java
                ).apply {
                    putExtra(EXTRA_SYMBOL, symbol)
                    putExtra(EXTRA_EXCHANGE, exchange)
                    putExtra(EXTRA_NAME, item.name)
                }
            )
        }

        binding.rvIndices.layoutManager =
            LinearLayoutManager(requireContext())

        binding.rvIndices.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(
                Lifecycle.State.STARTED
            ) {
                viewModel.indices.collect { list ->

                    adapter.submitList(list)

                    binding.tvMarketStatus.text =
                        if (list.isEmpty()) {
                            "No market data"
                        } else {
                            "Market data live"
                        }
                }
            }
        }
    }

    override fun onDestroyView() {
        binding.rvIndices.adapter = null
        _binding = null
        super.onDestroyView()
    }
}