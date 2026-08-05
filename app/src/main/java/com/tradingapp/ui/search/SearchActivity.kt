package com.tradingapp.ui.search

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tradingapp.data.model.Quote
import com.tradingapp.databinding.ActivitySearchBinding
import com.tradingapp.ui.adapter.RowAdapter
import com.tradingapp.ui.adapter.RowItem
import com.tradingapp.ui.chart.ChartActivity
import com.tradingapp.util.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private val viewModel: SearchViewModel by viewModels()
    private lateinit var adapter: RowAdapter
    private var lastResults: List<Quote> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupList()
        setupSearchBox()
        observeResults()

        binding.ivBack.setOnClickListener { finish() }
        binding.etSearch.requestFocus()
    }

    private fun setupList() {
        adapter = RowAdapter { row ->
            startActivity(Intent(this, ChartActivity::class.java).also {
                it.putExtra(EXTRA_SYMBOL, row.symbol)
                it.putExtra(EXTRA_EXCHANGE, row.exchange)
                it.putExtra(EXTRA_NAME, row.name)
            })
        }
        binding.rvResults.layoutManager = LinearLayoutManager(this)
        binding.rvResults.adapter = adapter
    }

    private fun setupSearchBox() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.onQueryChanged(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun observeResults() = lifecycleScope.launch {
        viewModel.results.collectLatest { res ->
            when (res) {
                is Resource.Loading -> {
                    binding.tvEmpty.visibility = android.view.View.GONE
                    binding.progressBar.visibility = android.view.View.VISIBLE
                }
                is Resource.Success -> {
                    binding.progressBar.visibility = android.view.View.GONE
                    lastResults = res.data
                    adapter.submitList(res.data.map { q ->
                        RowItem(
                            logo = q.symbol.take(2), name = q.symbol,
                            sub = (q.name ?: "").ifEmpty { q.exchange ?: "NSE" },
                            right = "", rightSub = "",
                            symbol = q.symbol, exchange = q.exchange ?: "NSE"
                        )
                    })
                    binding.tvEmpty.visibility =
                        if (res.data.isEmpty() && binding.etSearch.text.isNotEmpty())
                            android.view.View.VISIBLE else android.view.View.GONE
                    binding.tvEmpty.text = "No results for \"${binding.etSearch.text}\""
                }
                is Resource.Error -> {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.tvEmpty.visibility = android.view.View.VISIBLE
                    binding.tvEmpty.text = "Search error: ${res.message}"
                    adapter.submitList(emptyList())
                }
            }
        }
    }
}
