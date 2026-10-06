package com.tradingapp.ui.refer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tradingapp.R
import com.tradingapp.data.api.ReferralResponse
import com.tradingapp.data.api.TradexApi
import com.tradingapp.databinding.ActivityReferEarnBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Feature #6 — Refer & Earn.
 *
 * The reward amounts, the share URL, the share message template and the
 * T&C text all come from the server (app_settings), so the admin can
 * change the whole programme from /admin/manage.html without shipping a
 * new APK. The reward is credited automatically when the invited user's
 * KYC is approved.
 */
@AndroidEntryPoint
class ReferEarnActivity : AppCompatActivity() {

    @Inject lateinit var api: TradexApi
    private lateinit var b: ActivityReferEarnBinding
    private var data: ReferralResponse? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityReferEarnBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.ivBack.setOnClickListener { finish() }
        b.btnCopy.setOnClickListener  { copyCode() }
        b.tvCode.setOnClickListener   { copyCode() }
        b.btnShare.setOnClickListener { share() }

        load()
    }

    private fun load() = lifecycleScope.launch {
        b.progress.visibility = View.VISIBLE
        b.content.visibility  = View.GONE
        try {
            val r = api.referralInfo()
            b.progress.visibility = View.GONE

            if (!r.isSuccessful || r.body() == null) { fail("Could not load your referral details"); return@launch }

            val d = r.body()!!
            data = d
            b.content.visibility = View.VISIBLE

            if (!d.enabled) {
                b.tvHeadline.text = "Refer & Earn is paused"
                b.tvSubline.text  = "This programme is currently unavailable. Please check back later."
                b.cardCode.visibility = View.GONE
                b.btnShare.visibility = View.GONE
                b.sectionStats.visibility = View.GONE
                b.sectionList.visibility = View.GONE
                return@launch
            }

            b.tvHeadline.text = "Earn ₹%,.0f for every friend".format(d.rewardPerReferral)
            b.tvSubline.text =
                if (d.joiningBonus > 0)
                    "They get ₹%,.0f as a joining bonus, you get ₹%,.0f once their KYC is approved."
                        .format(d.joiningBonus, d.rewardPerReferral)
                else
                    "You get ₹%,.0f once their KYC is approved.".format(d.rewardPerReferral)

            b.tvCode.text = d.code ?: "—"

            b.tvStatInvited.text  = d.totalInvited.toString()
            b.tvStatRewarded.text = d.totalRewarded.toString()
            b.tvStatEarned.text   = "₹%,.0f".format(d.totalEarned)

            b.tvTerms.text = d.terms.orEmpty()
            b.tvTerms.visibility = if (d.terms.isNullOrBlank()) View.GONE else View.VISIBLE

            renderList(d)
        } catch (e: Exception) {
            b.progress.visibility = View.GONE
            fail(e.message ?: "Network error")
        }
    }

    private fun renderList(d: ReferralResponse) {
        b.listContainer.removeAllViews()
        if (d.referrals.isEmpty()) {
            b.tvNoReferrals.visibility = View.VISIBLE
            b.listContainer.visibility = View.GONE
            return
        }
        b.tvNoReferrals.visibility = View.GONE
        b.listContainer.visibility = View.VISIBLE

        d.referrals.forEach { item ->
            val row = layoutInflater.inflate(R.layout.item_referral, b.listContainer, false)
            row.findViewById<TextView>(R.id.tvReferralName).text = item.name ?: "TradeX user"
            row.findViewById<TextView>(R.id.tvReferralSub).text =
                when (item.kycStatus) {
                    "verified" -> "KYC verified"
                    "pending"  -> "KYC under review"
                    "rejected" -> "KYC rejected"
                    else       -> "Yet to submit KYC"
                }
            val badge = row.findViewById<TextView>(R.id.tvReferralBadge)
            if (item.status == "rewarded") {
                badge.text = "+₹%,.0f".format(item.reward)
                badge.setBackgroundResource(R.drawable.bg_badge_green)
            } else {
                badge.text = "PENDING"
                badge.setBackgroundResource(R.drawable.bg_badge_amber)
            }
            b.listContainer.addView(row)
        }
    }

    private fun fail(msg: String) {
        b.content.visibility = View.VISIBLE
        b.tvHeadline.text = "Something went wrong"
        b.tvSubline.text  = msg
        b.cardCode.visibility     = View.GONE
        b.btnShare.visibility     = View.GONE
        b.sectionStats.visibility = View.GONE
        b.sectionList.visibility  = View.GONE
    }

    private fun copyCode() {
        val code = data?.code ?: return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Referral code", code))
        Toast.makeText(this, "Referral code copied", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val text = data?.shareText
            ?: data?.shareLink
            ?: run { Toast.makeText(this, "Share link not configured", Toast.LENGTH_SHORT).show(); return }

        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Join me on TradeX")
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                "Invite friends via"
            )
        )
    }
}
