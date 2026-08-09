package com.tradingapp.ui.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.tradingapp.R
import com.tradingapp.data.model.Quote
import com.tradingapp.databinding.ActivityMainBinding
import com.tradingapp.ui.explore.ExploreFragment
import com.tradingapp.ui.explore.FnoExploreFragment
import com.tradingapp.ui.holdings.HoldingsFragment
import com.tradingapp.ui.orders.OrdersFragment
import com.tradingapp.ui.positions.PositionsFragment
import com.tradingapp.ui.profile.ProfileActivity
import com.tradingapp.ui.search.SearchActivity
import com.tradingapp.ui.watchlist.WatchlistFragment
import com.tradingapp.util.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val TABS = mapOf(
        SEG_EQUITY to listOf(TAB_EXPLORE, TAB_HOLDINGS, TAB_POSITIONS, TAB_ORDERS, TAB_WATCHLIST),
        SEG_FNO    to listOf(TAB_EXPLORE, TAB_POSITIONS, TAB_HOLDINGS, TAB_ORDERS, TAB_WATCHLIST),
        SEG_MF     to listOf(TAB_EXPLORE, TAB_HOLDINGS, TAB_ORDERS)
    )
    private val SEG_META = mapOf(
        SEG_EQUITY to Pair("Stocks",       "Equity · NSE / BSE"),
        SEG_FNO    to Pair("F&O",          "Futures & Options · NSE"),
        SEG_MF     to Pair("Mutual Funds", "Direct plans · zero commission")
    )

    private var currentTab     = TAB_EXPLORE
    private var currentSegment = SEG_EQUITY

    override fun onCreate(savedInstanceState: Bundle?) {
        // FIX #10: Edge-to-edge display with dark status bar icons
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // FIX #10: Light-coloured icons on dark background
        WindowInsetsControllerCompat(window, binding.root).isAppearanceLightStatusBars = false

        setupBottomNav()
        setupSearch()
        setupProfileIcon()
        observeSegment()
        observeIndices()
        switchSegment(SEG_EQUITY)
    }

    // FIX #1 — wire the profile avatar circle to open ProfileActivity
    private fun setupProfileIcon() {
        binding.tvAvatar.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        // Set user initials from SharedPreferences
        val prefs    = getSharedPreferences("tradingapp_prefs", Context.MODE_PRIVATE)
        val name     = prefs.getString("user_name", "") ?: ""
        val initials = name.split(" ").filter { it.isNotEmpty() }
            .take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "RJ" }
        binding.tvAvatar.text = initials
    }

    private fun setupSearch() {
        binding.ivSearch.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
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
        viewModel.indices.collectLatest { list -> buildIndexTicker(list) }
    }

    private fun buildIndexTicker(indices: List<Quote>) {
        binding.llIndices.removeAllViews()
        indices.forEach { q ->
            val view = layoutInflater.inflate(R.layout.item_index, binding.llIndices, false)
            view.findViewById<TextView>(R.id.tvName).text = q.symbol.substringAfter(":")
            val valueTv  = view.findViewById<TextView>(R.id.tvValue)
            val changeTv = view.findViewById<TextView>(R.id.tvChange)
            if (q.ltp <= 0.0) {
                valueTv.text = "—"; changeTv.text = "…"; changeTv.setTextColor(0xFF6E7681.toInt())
            } else {
                valueTv.text = "%.2f".format(q.ltp)
                changeTv.text = if (q.changePct >= 0) "+%.2f%%".format(q.changePct) else "%.2f%%".format(q.changePct)
                changeTv.setTextColor(if (q.isPositive) 0xFF2FBF71.toInt() else 0xFFFF5C5C.toInt())
            }
            binding.llIndices.addView(view)
        }
    }

    private fun buildTabStrip(segment: String) {
        binding.llTabs.removeAllViews()
        TABS[segment]?.forEach { tab ->
            val tv = TextView(this).apply {
                text = tab; setPadding(40, 52, 40, 44); textSize = 13.5f
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
        val fragment = when {
            tab == TAB_EXPLORE && currentSegment == SEG_FNO -> FnoExploreFragment.newInstance(currentSegment)
            tab == TAB_EXPLORE   -> ExploreFragment.newInstance(currentSegment)
            tab == TAB_HOLDINGS  -> HoldingsFragment.newInstance(currentSegment)
            tab == TAB_POSITIONS -> PositionsFragment.newInstance(currentSegment)
            tab == TAB_ORDERS    -> OrdersFragment.newInstance(currentSegment)
            tab == TAB_WATCHLIST -> WatchlistFragment.newInstance(currentSegment)
            else                 -> ExploreFragment.newInstance(currentSegment)
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentHost, fragment)
            .commit()
    }

    private fun updateTabHighlight() {
        for (i in 0 until binding.llTabs.childCount) {
            val tv = binding.llTabs.getChildAt(i) as? TextView ?: continue
            val a = tv.tag == currentTab
            tv.setTextColor(if (a) 0xFFF2F4F7.toInt() else 0xFF7C848F.toInt())
            tv.setTypeface(tv.typeface, if (a) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    private fun setupBottomNav() {
        binding.navStocks.setOnClickListener { viewModel.setSegment(SEG_EQUITY) }
        binding.navFno.setOnClickListener    { viewModel.setSegment(SEG_FNO) }
        binding.navFunds.setOnClickListener  { viewModel.setSegment(SEG_MF) }
    }

    private fun switchSegment(seg: String) = viewModel.setSegment(seg)
}
