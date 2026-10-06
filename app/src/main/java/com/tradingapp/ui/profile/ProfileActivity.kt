package com.tradingapp.ui.profile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.tradingapp.BuildConfig
import com.tradingapp.R
import com.tradingapp.data.api.TradexApi
import com.tradingapp.databinding.ActivityProfileBinding
import com.tradingapp.ui.auth.LoginActivity
import com.tradingapp.ui.orders.MyOrdersActivity
import com.tradingapp.ui.refer.ReferEarnActivity
import com.tradingapp.ui.support.SupportActivity
import com.tradingapp.ui.wallet.AddFundsActivity
import com.tradingapp.ui.wallet.WithdrawActivity
import com.tradingapp.ui.web.WebViewActivity
import com.tradingapp.util.applyEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Features #3, #4, #5, #6, #7, #8.
 *
 * Previously six rows here were wired to empty `setOnClickListener {}`
 * blocks, so nothing happened when they were tapped. Each one now opens a
 * real screen.
 *
 * Two things are done in code rather than by editing activity_profile.xml
 * or MainActivity.kt, so that you only ever copy whole files:
 *
 *  - The "Withdraw funds" row is inflated and inserted into the profile
 *    column at runtime (see installWithdrawRow).
 *  - The KYC gate lives here instead of on the avatar tap in MainActivity:
 *    if the account is not verified we hand straight over to
 *    AccountStatusActivity before anything is drawn.
 */
@AndroidEntryPoint
class ProfileActivity : AppCompatActivity() {

    @Inject lateinit var api: TradexApi

    private lateinit var b: ActivityProfileBinding
    private val vm: ProfileViewModel by viewModels()

    // URLs for the two WebView buttons, fetched from the server (#8)
    private var aboutUrl: String? = null
    private var chargesUrl: String? = null

    private val walletLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> if (result.resultCode == RESULT_OK) vm.load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // -- FEATURE #3: gate before drawing anything -------------
        // Uses the cached status so there is no flash of the wrong
        // screen; guardKyc() below then confirms it with the server.
        val cached = getSharedPreferences(AccountStatusActivity.PREFS, Context.MODE_PRIVATE)
            .getString(AccountStatusActivity.KEY_KYC, null)
        if (cached != null && cached != "verified") {
            startActivity(Intent(this, AccountStatusActivity::class.java))
            finish()
            return
        }

        b = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(b.root)

        runCatching { applyEdgeToEdge(topView = b.root.findViewById(R.id.profileHeader)) }
        WindowInsetsControllerCompat(window, b.root).isAppearanceLightStatusBars = false

        b.ivBack.setOnClickListener { finish() }
        b.tvVersion.text = "v${BuildConfig.VERSION_NAME}"

        // -- #4 Orders --------------------------------------------
        b.rowOrders.setOnClickListener {
            startActivity(Intent(this, MyOrdersActivity::class.java))
        }

        // Reports - not part of this patch; say so rather than do nothing
        b.rowReports.setOnClickListener {
            Toast.makeText(this, "Statements and tax reports are coming soon.",
                Toast.LENGTH_SHORT).show()
        }

        // -- #6 Refer & earn --------------------------------------
        b.rowRefer.setOnClickListener {
            startActivity(Intent(this, ReferEarnActivity::class.java))
        }

        // -- #5 Customer support ----------------------------------
        b.rowSupport.setOnClickListener {
            startActivity(Intent(this, SupportActivity::class.java))
        }

        // -- #8 About / Charges open in an in-app WebView ---------
        b.tvAbout.setOnClickListener   { openLink(aboutUrl, "About TradeX") }
        b.tvCharges.setOnClickListener { openLink(chargesUrl, "Charges & pricing") }

        // Account details
        b.rowAccount.setOnClickListener {
            startActivity(Intent(this, AccountInfoActivity::class.java))
        }

        // Add money (unchanged)
        b.btnAddMoney.setOnClickListener {
            walletLauncher.launch(Intent(this, AddFundsActivity::class.java))
        }

        // -- #7 Withdraw funds ------------------------------------
        installWithdrawRow()

        b.rowLogout.setOnClickListener {
            vm.logout {
                startActivity(
                    Intent(this, LoginActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            }
        }

        lifecycleScope.launchWhenStarted {
            vm.state.collectLatest { s ->
                b.tvInitials.text  = s.initials
                b.tvUserName.text  = s.name.ifEmpty { "Investor" }
                b.tvUserEmail.text = s.email
                b.tvBalance.text   = "\u20B9%.2f".format(s.balance)
            }
        }

        loadLinks()
    }

    override fun onResume() {
        super.onResume()
        if (!this::b.isInitialized) return
        guardKyc()
        vm.load()
    }

    /**
     * Inserts the "Withdraw funds" card into the profile column without
     * touching activity_profile.xml.
     *
     * rowOrders sits inside a LinearLayout, inside a CardView, inside the
     * scrolling vertical column - so walking up three levels gives us the
     * column and the position to insert at. Wrapped in runCatching: if the
     * layout is ever restructured the row simply does not appear, rather
     * than crashing the profile screen.
     */
    private fun installWithdrawRow() {
        runCatching {
            val card   = b.rowOrders.parent.parent as View
            val column = card.parent as ViewGroup
            val at     = column.indexOfChild(card)

            val block = layoutInflater.inflate(R.layout.view_profile_withdraw, column, false)
            block.findViewById<View>(R.id.rowWithdraw).setOnClickListener {
                walletLauncher.launch(Intent(this, WithdrawActivity::class.java))
            }
            column.addView(block, at)
        }
    }

    /**
     * FEATURE #3 - confirm the KYC status with the server and keep the
     * cached value fresh. We only redirect on a SUCCESSFUL response, so a
     * dropped connection never locks anyone out of their own profile.
     */
    private fun guardKyc() = lifecycleScope.launch {
        try {
            val r = api.kycStatusFull()
            if (!r.isSuccessful) return@launch
            val status = r.body()?.kycStatus ?: return@launch

            getSharedPreferences(AccountStatusActivity.PREFS, Context.MODE_PRIVATE)
                .edit().putString(AccountStatusActivity.KEY_KYC, status).apply()

            if (status != "verified") {
                startActivity(Intent(this@ProfileActivity, AccountStatusActivity::class.java))
                finish()
            }
        } catch (_: Exception) { }
    }

    private fun loadLinks() = lifecycleScope.launch {
        try {
            val r = api.appLinks()
            if (r.isSuccessful) {
                aboutUrl   = r.body()?.about
                chargesUrl = r.body()?.charges
            }
        } catch (_: Exception) { }
    }

    private fun openLink(url: String?, title: String) {
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "$title is not available right now.", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(WebViewActivity.intent(this, url, title))
    }
}
