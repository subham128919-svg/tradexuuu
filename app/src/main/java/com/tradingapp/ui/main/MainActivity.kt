package com.tradingapp.ui.main

import android.os.Bundle
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tradingapp.R
import com.tradingapp.databinding.ActivityMainBinding
import com.tradingapp.data.model.Quote
import com.tradingapp.ui.explore.ExploreFragment
import com.tradingapp.ui.holdings.HoldingsFragment
import com.tradingapp.ui.orders.OrdersFragment
import com.tradingapp.ui.positions.PositionsFragment
import com.tradingapp.ui.watchlist.WatchlistFragment
import com.tradingapp.util.*          // ← fixed: package star import, not object star import
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val TABS = mapOf(
        SEG_EQUITY to listOf(TAB_EXPLORE, TAB_HOLDINGS, TAB_POSITIONS, TAB_ORDERS, TAB_WATCHLIST),
        SEG_FNO    to listOf(TAB_EXPLORE, TAB_POSITIONS, TAB_HOLDINGS, TAB_ORDERS, TAB_WATCHLIST),
        SEG_MF     to listOf(TAB_EXPLORE, TAB_HOLDINGS, TAB_ORDERS, TAB_SIPS)
    )
    private val SEG_META = mapOf(
        SEG_EQUITY to Pair("Stocks",       "Equity · NSE / BSE"),
        SEG_FNO    to Pair("F&O",          "Futures & Options · NSE"),
        SEG_MF     to Pair("Mutual Funds", "Direct plans · zero commission")
    )

    private var currentTab     = TAB_EXPLORE
    private var currentSegment = SEG_EQUITY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupBottomNav()
        observeSegment()
        observeIndices()
        switchSegment(SEG_EQUITY)
    }

    private fun observeSegment() = lifecycleScope.launchWhenStarted {
        viewModel.segment.collectLatest { seg ->
            currentSegment = seg
            val meta = SEG_META[seg]!!
            binding.tvSegTitle.text = meta.first
            binding.tvSegSub.text   = meta.second
            buildTabStrip(seg)
            switchTab(TABS[seg]!!.first())
        }
    }

    private fun observeIndices() = lifecycleScope.launchWhenStarted {
        viewModel.indices.collectLatest { res ->
            if (res is Resource.Success) buildIndexTicker(res.data)
        }
    }

    private fun buildIndexTicker(indices: List<Quote>) {
        binding.llIndices.removeAllViews()
        indices.forEach { q ->
            val view = layoutInflater.inflate(R.layout.item_index, binding.llIndices, false)
            view.findViewById<TextView>(R.id.tvName).text  = q.symbol.substringAfter(":")
            view.findViewById<TextView>(R.id.tvValue).text = "%.2f".format(q.ltp)
            val changeTv = view.findViewById<TextView>(R.id.tvChange)
            changeTv.text = if (q.changePct >= 0) "+%.2f%%".format(q.changePct) else "%.2f%%".format(q.changePct)
            changeTv.setTextColor(if (q.isPositive) 0xFF2FBF71.toInt() else 0xFFFF5C5C.toInt())
            binding.llIndices.addView(view)
        }
    }

    private fun buildTabStrip(segment: String) {
        binding.llTabs.removeAllViews()
        TABS[segment]?.forEach { tab ->
            val tv = TextView(this).apply {
                text     = tab
                setPadding(40, 52, 40, 44)
                textSize = 13.5f
                setTextColor(if (tab == currentTab) 0xFFF2F4F7.toInt() else 0xFF7C848F.toInt())
                setTypeface(typeface, if (tab == currentTab) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setOnClickListener { switchTab(tab) }
                tag = tab
            }
            binding.llTabs.addView(tv)
        }
    }

    private fun switchTab(tab: String) {
        currentTab = tab
        updateTabHighlight()
        val fragment = when (tab) {
            TAB_EXPLORE   -> ExploreFragment.newInstance(currentSegment)
            TAB_HOLDINGS  -> HoldingsFragment.newInstance(currentSegment)
            TAB_POSITIONS -> PositionsFragment.newInstance(currentSegment)
            TAB_ORDERS    -> OrdersFragment.newInstance(currentSegment)
            TAB_WATCHLIST -> WatchlistFragment.newInstance(currentSegment)
            else          -> ExploreFragment.newInstance(currentSegment)
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentHost, fragment)
            .commit()
    }

    private fun updateTabHighlight() {
        for (i in 0 until binding.llTabs.childCount) {
            val tv = binding.llTabs.getChildAt(i) as? TextView ?: continue
            val active = tv.tag == currentTab
            tv.setTextColor(if (active) 0xFFF2F4F7.toInt() else 0xFF7C848F.toInt())
            tv.setTypeface(tv.typeface, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    private fun setupBottomNav() {
        binding.navStocks.setOnClickListener { viewModel.setSegment(SEG_EQUITY) }
        binding.navFno.setOnClickListener    { viewModel.setSegment(SEG_FNO) }
        binding.navFunds.setOnClickListener  { viewModel.setSegment(SEG_MF) }
    }

    private fun switchSegment(seg: String) = viewModel.setSegment(seg)
}
