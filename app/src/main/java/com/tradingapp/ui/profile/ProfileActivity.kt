package com.tradingapp.ui.profile

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.tradingapp.BuildConfig
import com.tradingapp.R
import com.tradingapp.databinding.ActivityProfileBinding
import com.tradingapp.ui.auth.LoginActivity
import com.tradingapp.ui.wallet.AddFundsActivity
import com.tradingapp.util.applyEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest

@AndroidEntryPoint
class ProfileActivity : AppCompatActivity() {

    private lateinit var b: ActivityProfileBinding
    private val vm: ProfileViewModel by viewModels()

    // Refreshes the displayed balance immediately when the user comes
    // back from successfully adding funds, rather than waiting for
    // whatever next triggers a reload.
    private val addFundsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) vm.load()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(b.root)

        // FIX #10: edge-to-edge — the header row (back/bell/settings)
        // needs an id to get top-inset padding. See patch note below
        // if this doesn't take effect: activity_profile.xml's header
        // LinearLayout needs android:id="@+id/profileHeader" added.
        runCatching {
            applyEdgeToEdge(topView = b.root.findViewById(R.id.profileHeader))
        }
        WindowInsetsControllerCompat(window, b.root).isAppearanceLightStatusBars = false

        b.ivBack.setOnClickListener { finish() }
        b.tvVersion.text = "v${BuildConfig.VERSION_NAME}"

        b.rowOrders.setOnClickListener  {}
        b.rowReports.setOnClickListener {}
        b.rowAccount.setOnClickListener {}
        b.rowRefer.setOnClickListener   {}
        b.rowSupport.setOnClickListener {}
        b.tvAbout.setOnClickListener    {}
        b.tvCharges.setOnClickListener  {}
        b.btnAddMoney.setOnClickListener {
            addFundsLauncher.launch(Intent(this, AddFundsActivity::class.java))
        }

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
