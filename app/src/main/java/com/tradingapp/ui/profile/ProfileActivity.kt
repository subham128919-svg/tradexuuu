package com.tradingapp.ui.profile

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tradingapp.BuildConfig
import com.tradingapp.databinding.ActivityProfileBinding
import com.tradingapp.ui.auth.LoginActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest

@AndroidEntryPoint
class ProfileActivity : AppCompatActivity() {

    private lateinit var b: ActivityProfileBinding
    private val vm: ProfileViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.ivBack.setOnClickListener { finish() }
        b.tvVersion.text = "v${BuildConfig.VERSION_NAME}"

        // Row click handlers
        b.rowOrders.setOnClickListener  { /* TODO: open orders screen */ }
        b.rowReports.setOnClickListener { /* TODO: open reports */ }
        b.rowAccount.setOnClickListener { /* TODO: account details */ }
        b.rowRefer.setOnClickListener   { /* TODO: referral */ }
        b.rowSupport.setOnClickListener { /* TODO: support */ }
        b.tvAbout.setOnClickListener    { /* TODO: about */ }
        b.tvCharges.setOnClickListener  { /* TODO: charges */ }

        b.btnAddMoney.setOnClickListener { /* Wallet flow — gateway not live yet */ }

        b.rowLogout.setOnClickListener {
            vm.logout {
                startActivity(Intent(this, LoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
        }

        lifecycleScope.launchWhenStarted {
            vm.state.collectLatest { s ->
                b.tvInitials.text  = s.initials
                b.tvUserName.text  = s.name.ifEmpty { "Investor" }
                b.tvUserEmail.text = s.email
                b.tvBalance.text   = "₹%.2f".format(s.balance)
            }
        }
    }
}
