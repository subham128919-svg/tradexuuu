package com.tradingapp.ui.profile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tradingapp.data.api.TradexApi
import com.tradingapp.databinding.ActivityAccountStatusBinding
import com.tradingapp.ui.auth.LoginActivity
import com.tradingapp.ui.kyc.KycUploadActivity
import com.tradingapp.ui.support.SupportActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Feature #3 — the gate in front of the real profile.
 *
 * While the account is awaiting (or has failed) admin verification the
 * user can still browse the whole app, but tapping the profile avatar
 * lands here instead of ProfileActivity. The moment the admin approves
 * the KYC, this screen forwards straight to the real profile.
 */
@AndroidEntryPoint
class AccountStatusActivity : AppCompatActivity() {

    @Inject lateinit var api: TradexApi
    private lateinit var b: ActivityAccountStatusBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAccountStatusBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.ivBack.setOnClickListener { finish() }
        b.btnRefresh.setOnClickListener { load(showSpinner = true) }
        b.btnSupport.setOnClickListener {
            startActivity(Intent(this, SupportActivity::class.java))
        }
        b.btnLogout.setOnClickListener {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            startActivity(
                Intent(this, LoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
        b.btnPrimary.setOnClickListener {
            // "Upload documents" / "Re-upload documents"
            startActivity(Intent(this, KycUploadActivity::class.java))
        }

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        b.tvName.text  = prefs.getString("user_name", "") ?: "Investor"
        b.tvEmail.text = prefs.getString("user_email", "") ?: ""
        b.tvInitials.text = (prefs.getString("user_name", "") ?: "")
            .split(" ").filter { it.isNotEmpty() }.take(2)
            .joinToString("") { it.first().uppercase() }.ifEmpty { "??" }
    }

    override fun onResume() {
        super.onResume()
        load(showSpinner = false)
    }

    private fun load(showSpinner: Boolean) = lifecycleScope.launch {
        if (showSpinner) b.progress.visibility = View.VISIBLE
        try {
            val r = api.kycStatusFull()
            b.progress.visibility = View.GONE
            if (!r.isSuccessful) { render(null, null); return@launch }

            val body   = r.body()
            val status = body?.kycStatus ?: "none"

            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_KYC, status).apply()

            if (status == "verified") {
                // Approved while they were waiting — go to the real profile.
                startActivity(Intent(this@AccountStatusActivity, ProfileActivity::class.java))
                finish()
                return@launch
            }
            render(status, body?.remarks)
        } catch (e: Exception) {
            b.progress.visibility = View.GONE
            render(null, null)
        }
    }

    private fun render(status: String?, remarks: String?) {
        when (status) {
            "pending" -> {
                b.tvStatusBadge.text = "UNDER REVIEW"
                b.tvStatusBadge.setBackgroundResource(com.tradingapp.R.drawable.bg_badge_amber)
                b.tvHeadline.text = "Your account is being verified"
                b.tvBody.text =
                    "We have received your documents. Our team usually completes " +
                    "verification within 24 working hours.\n\n" +
                    "You can keep exploring the app meanwhile — live prices, charts, " +
                    "watchlists and research are all open. Trading, funds and your " +
                    "full profile unlock once verification is done."
                b.btnPrimary.visibility = View.GONE
                b.tvRemarks.visibility  = View.GONE
                setSteps(docsDone = true, reviewDone = false)
            }
            "rejected" -> {
                b.tvStatusBadge.text = "ACTION NEEDED"
                b.tvStatusBadge.setBackgroundResource(com.tradingapp.R.drawable.bg_badge_red)
                b.tvHeadline.text = "We could not verify your documents"
                b.tvBody.text =
                    "Please upload your PAN and Aadhaar again, making sure all four " +
                    "corners are visible and the text is readable."
                b.btnPrimary.text = "Re-upload documents"
                b.btnPrimary.visibility = View.VISIBLE
                if (!remarks.isNullOrBlank()) {
                    b.tvRemarks.text = "Reason: $remarks"
                    b.tvRemarks.visibility = View.VISIBLE
                } else b.tvRemarks.visibility = View.GONE
                setSteps(docsDone = true, reviewDone = false)
            }
            "none" -> {
                b.tvStatusBadge.text = "NOT SUBMITTED"
                b.tvStatusBadge.setBackgroundResource(com.tradingapp.R.drawable.bg_badge_grey)
                b.tvHeadline.text = "Finish setting up your account"
                b.tvBody.text =
                    "Upload your PAN and Aadhaar to activate trading, funds and your " +
                    "full profile. It takes about two minutes."
                b.btnPrimary.text = "Upload documents"
                b.btnPrimary.visibility = View.VISIBLE
                b.tvRemarks.visibility = View.GONE
                setSteps(docsDone = false, reviewDone = false)
            }
            else -> {
                b.tvStatusBadge.text = "OFFLINE"
                b.tvStatusBadge.setBackgroundResource(com.tradingapp.R.drawable.bg_badge_grey)
                b.tvHeadline.text = "Could not reach the server"
                b.tvBody.text = "Check your internet connection and tap Refresh."
                b.btnPrimary.visibility = View.GONE
                b.tvRemarks.visibility  = View.GONE
            }
        }
    }

    private fun setSteps(docsDone: Boolean, reviewDone: Boolean) {
        b.tvStep1.text = "✓  Account created"
        b.tvStep2.text = (if (docsDone) "✓" else "○") + "  Documents submitted"
        b.tvStep3.text = (if (reviewDone) "✓" else "○") + "  Admin verification"
        b.tvStep4.text = "○  Demat account activated"
    }

    companion object {
        const val PREFS   = "tradingapp_prefs"
        const val KEY_KYC = "kyc_status"

        /**
         * Single place that decides where the profile avatar should go.
         * Call from MainActivity (and anywhere else with an avatar).
         */
        fun routeFromAvatar(ctx: Context): Intent {
            val prefs  = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val status = prefs.getString(KEY_KYC, null)
            return if (status == "verified")
                Intent(ctx, ProfileActivity::class.java)
            else
                Intent(ctx, AccountStatusActivity::class.java)
        }
    }
}
