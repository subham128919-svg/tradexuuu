package com.tradingapp.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
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
        SEG_EQUITY to listOf(
            TAB_EXPLORE,
            TAB_HOLDINGS,
            TAB_POSITIONS,
            TAB_ORDERS,
            TAB_WATCHLIST
        ),
        SEG_FNO to listOf(
            TAB_EXPLORE,
            TAB_POSITIONS,
            TAB_HOLDINGS,
            TAB_ORDERS,
            TAB_WATCHLIST
        ),
        SEG_MF to listOf(
            TAB_EXPLORE,
            TAB_HOLDINGS,
            TAB_ORDERS
        )
    )

    private val SEG_META = mapOf(
        SEG_EQUITY to Pair("Stocks", "Equity · NSE / BSE"),
        SEG_FNO to Pair("F&O", "Futures & Options · NSE"),
        SEG_MF to Pair("Mutual Funds", "Direct plans · zero commission")
    )

    private var currentTab = TAB_EXPLORE
    private var currentSegment = SEG_EQUITY
    private val selectedTabs = mutableMapOf<String, String>()
    private var displayedTag: String? = null
    private var observedSegment: String? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyEdgeToEdge(
            topView = binding.appHeader,
            bottomView = binding.bottomNav
        )

        WindowInsetsControllerCompat(
            window,
            binding.root
        ).isAppearanceLightStatusBars = false

        setupBottomNav()
        setupSearch()
        setupProfileIcon()

        currentSegment = savedInstanceState?.getString("segment") ?: SEG_EQUITY
        currentTab = savedInstanceState?.getString("tab") ?: TAB_EXPLORE
        SEG_META.keys.forEach { seg ->
            selectedTabs[seg] = savedInstanceState?.getString("tab:$seg") ?: TAB_EXPLORE
        }
        selectedTabs[currentSegment] = currentTab
        viewModel.setSegment(currentSegment)
        observeSegment()
        observeIndices()
        lifecycleScope.launchWhenStarted {
            viewModel.connectionState.collectLatest { state ->
                val status = when (state) {
                    is com.tradingapp.data.websocket.ConnectionState.Connected -> "connected"
                    is com.tradingapp.data.websocket.ConnectionState.Reconnecting -> "reconnecting"
                    is com.tradingapp.data.websocket.ConnectionState.Disconnected -> "prices unavailable"
                    is com.tradingapp.data.websocket.ConnectionState.Degraded -> "prices incomplete"
                    else -> "connecting"
                }
                binding.tvSegSub.text = "${SEG_META[currentSegment]?.second.orEmpty()} · $status"
            }
        }

    }


    private fun setupProfileIcon() {

        binding.avatarWrap.setOnClickListener {
            startActivity(
                Intent(
                    this,
                    ProfileActivity::class.java
                )
            )
        }

        val prefs = getSharedPreferences(
            "tradingapp_prefs",
            Context.MODE_PRIVATE
        )

        val name =
            prefs.getString("user_name", "") ?: ""

        val initials =
            name.split(" ")
                .filter { it.isNotEmpty() }
                .take(2)
                .joinToString("") {
                    it.first().uppercase()
                }
                .ifEmpty { "RJ" }

        binding.tvAvatar.text = initials

        val hasSession =
            !prefs.getString(
                "jwt_token",
                null
            ).isNullOrEmpty()

        binding.dotOnline.visibility =
            if (hasSession) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }


    private fun setupSearch() {

        binding.ivSearch.setOnClickListener {
            startActivity(
                Intent(
                    this,
                    SearchActivity::class.java
                )
            )
        }
    }


    private fun observeSegment() =
        lifecycleScope.launchWhenStarted {

            viewModel.segment.collectLatest { seg ->

                if (observedSegment == seg) return@collectLatest
                observedSegment = seg
                currentSegment = seg
                currentTab = selectedTabs[seg] ?: TAB_EXPLORE

                val meta = SEG_META[seg]
                    ?: return@collectLatest

                binding.tvSegTitle.text = meta.first
                binding.tvSegSub.text = meta.second

                buildTabStrip(seg)

                switchTab(currentTab)

                updateBottomNavHighlight(seg)
            }
        }


    private fun observeIndices() =
        lifecycleScope.launchWhenStarted {

            viewModel.indices.collectLatest { list ->
                buildIndexTicker(list)
            }
        }


    private fun buildIndexTicker(
        indices: List<Quote>
    ) {

        if (binding.llIndices.childCount != indices.size) {
            binding.llIndices.removeAllViews()
            indices.forEach { _ ->
                binding.llIndices.addView(layoutInflater.inflate(R.layout.item_index, binding.llIndices, false))
            }
        }

        indices.forEachIndexed { index, q ->

            val view = binding.llIndices.getChildAt(index)

            view.findViewById<TextView>(
                R.id.tvName
            ).text =
                q.symbol.substringAfter(":")

            val valueTv =
                view.findViewById<TextView>(
                    R.id.tvValue
                )

            val changeTv =
                view.findViewById<TextView>(
                    R.id.tvChange
                )

            if (q.ltp <= 0.0) {

                valueTv.text = "—"
                changeTv.text = "…"

                changeTv.setTextColor(
                    0xFF777777.toInt()
                )

            } else {

                valueTv.text =
                    "%.2f".format(q.ltp)

                changeTv.text =
                    if (q.changePct >= 0) {
                        "+%.2f%%".format(
                            q.changePct
                        )
                    } else {
                        "%.2f%%".format(
                            q.changePct
                        )
                    }

                changeTv.setTextColor(
                    if (q.isPositive) {
                        0xFF2FBF71.toInt()
                    } else {
                        0xFFE05252.toInt()
                    }
                )
            }

        }
    }


    /*
     * ============================================================
     * MAIN TAB STRIP
     *
     * Same visual language as the chart timeframe buttons:
     *
     * ACTIVE
     *   black background
     *   white text
     *
     * INACTIVE
     *   light gray background
     *   dark text
     * ============================================================
     */

    private fun buildTabStrip(
        segment: String
    ) {

        binding.llTabs.removeAllViews()

        val tabs =
            TABS[segment]
                ?: return

        tabs.forEach { tab ->

            val tv =
                TextView(this).apply {

                    text = tab

                    textSize = 12f

                    gravity =
                        android.view.Gravity.CENTER

                    includeFontPadding = false

                    minWidth = dp(72)

                    minHeight = dp(34)

                    setPadding(
                        dp(14),
                        0,
                        dp(14),
                        0
                    )

                    tag = tab

                    setTypeface(
                        typeface,
                        if (tab == currentTab) {
                            Typeface.BOLD
                        } else {
                            Typeface.NORMAL
                        }
                    )

                    setTextColor(
                        if (tab == currentTab) {
                            0xFFFFFFFF.toInt()
                        } else {
                            0xFF333333.toInt()
                        }
                    )

                    setBackgroundResource(
                        if (tab == currentTab) {
                            R.drawable.bg_chart_period_active
                        } else {
                            R.drawable.bg_chart_period
                        }
                    )

                    setOnClickListener {
                        switchTab(tab)
                    }
                }


            val params =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(34)
                ).apply {

                    marginEnd = dp(7)
                }

            binding.llTabs.addView(
                tv,
                params
            )
        }
    }


    private fun switchTab(
        tab: String
    ) {

        currentTab = tab
        selectedTabs[currentSegment] = tab

        updateTabHighlight()
        val tag = "$currentSegment:$tab"
        if (displayedTag == tag || supportFragmentManager.isStateSaved) return
        val started = System.nanoTime()
        val existing = supportFragmentManager.findFragmentByTag(tag)
        val cached = existing?.view != null

        val fragment =
            existing ?: when {

                tab == TAB_EXPLORE &&
                        currentSegment == SEG_FNO ->
                    FnoExploreFragment.newInstance(
                        currentSegment
                    )

                tab == TAB_EXPLORE ->
                    ExploreFragment.newInstance(
                        currentSegment
                    )

                tab == TAB_HOLDINGS ->
                    HoldingsFragment.newInstance(
                        currentSegment
                    )

                tab == TAB_POSITIONS ->
                    PositionsFragment.newInstance(
                        currentSegment
                    )

                tab == TAB_ORDERS ->
                    OrdersFragment.newInstance(
                        currentSegment
                    )

                tab == TAB_WATCHLIST ->
                    WatchlistFragment.newInstance(
                        currentSegment
                    )

                else ->
                    ExploreFragment.newInstance(
                        currentSegment
                    )
            }

        val transaction = supportFragmentManager.beginTransaction().setReorderingAllowed(true)
        supportFragmentManager.fragments.filter { it.id == R.id.fragmentHost && it != fragment }.forEach {
            transaction.hide(it).setMaxLifecycle(it, Lifecycle.State.STARTED)
        }
        if (!fragment.isAdded) transaction.add(R.id.fragmentHost, fragment, tag)
        transaction.show(fragment).setMaxLifecycle(fragment, Lifecycle.State.RESUMED).commitNow()
        displayedTag = tag
        fragment.view?.doOnPreDraw {
            if(com.tradingapp.BuildConfig.DEBUG) android.util.Log.d("TabTiming", "$tag cached=$cached elapsedMs=${(System.nanoTime()-started)/1_000_000.0}")
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        switchTab(currentTab)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("segment", currentSegment)
        outState.putString("tab", currentTab)
        selectedTabs.forEach { (seg, tab) -> outState.putString("tab:$seg", tab) }
        super.onSaveInstanceState(outState)
    }


    /*
     * Update ONLY the visual state.
     * The selected tab gets the same black/white style
     * used by the chart timeline.
     */

    private fun updateTabHighlight() {

        for (
        i in 0 until binding.llTabs.childCount
        ) {

            val tv =
                binding.llTabs
                    .getChildAt(i) as? TextView
                    ?: continue

            val active =
                tv.tag == currentTab

            tv.setTextColor(
                if (active) {
                    0xFFFFFFFF.toInt()
                } else {
                    0xFF333333.toInt()
                }
            )

            tv.setTypeface(
                tv.typeface,
                if (active) {
                    Typeface.BOLD
                } else {
                    Typeface.NORMAL
                }
            )

            tv.setBackgroundResource(
                if (active) {
                    R.drawable.bg_chart_period_active
                } else {
                    R.drawable.bg_chart_period
                }
            )
        }
    }


    private fun setupBottomNav() {

        binding.navStocks.setOnClickListener {
            viewModel.setSegment(
                SEG_EQUITY
            )
        }

        binding.navFno.setOnClickListener {
            viewModel.setSegment(
                SEG_FNO
            )
        }

        binding.navFunds.setOnClickListener {
            viewModel.setSegment(
                SEG_MF
            )
        }
    }


    private fun updateBottomNavHighlight(
        segment: String
    ) {

        setNavItemActive(
            binding.navIconBgStocks,
            binding.tvNavStocks,
            segment == SEG_EQUITY
        )

        setNavItemActive(
            binding.navIconBgFno,
            binding.tvNavFno,
            segment == SEG_FNO
        )

        setNavItemActive(
            binding.navIconBgFunds,
            binding.tvNavFunds,
            segment == SEG_MF
        )
    }


    private fun setNavItemActive(
        iconBg: FrameLayout,
        label: TextView,
        active: Boolean
    ) {

        iconBg.setBackgroundResource(
            if (active) {
                R.drawable.bg_nav_on
            } else {
                android.R.color.transparent
            }
        )

        iconBg.alpha =
            if (active) 1f else 0.7f

        label.setTextColor(
            if (active) {
                0xFFF2F4F7.toInt()
            } else {
                0xFF777777.toInt()
            }
        )

        label.setTypeface(
            label.typeface,
            if (active) {
                Typeface.BOLD
            } else {
                Typeface.NORMAL
            }
        )
    }


    private fun switchSegment(
        seg: String
    ) {
        viewModel.setSegment(seg)
    }


    private fun dp(
        value: Int
    ): Int =
        (value * resources.displayMetrics.density)
            .toInt()
}
