package com.tradingapp.ui.profile

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tradingapp.data.api.ApiService
import com.tradingapp.databinding.ActivityAccountInfoBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AccountInfoActivity : AppCompatActivity() {

    private lateinit var b: ActivityAccountInfoBinding

    @Inject lateinit var api: ApiService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAccountInfoBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.ivBack.setOnClickListener { finish() }

        loadAccountInfo()
    }

    private fun loadAccountInfo() {
        b.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val r = api.getAccountInfo()
                b.progressBar.visibility = View.GONE

                if (r.isSuccessful && r.body() != null) {
                    val info = r.body()!!

                    b.tvName.text  = info.name
                    b.tvEmail.text = info.email
                    b.tvPhone.text = info.phone ?: "—"
                    b.tvJoinedAt.text = info.joinedAt?.take(10) ?: "—"

                    // KYC Status
                    when (info.kycStatus) {
                        "verified" -> {
                            b.tvKycStatus.text = "✓ Verified"
                            b.tvKycStatus.setTextColor(0xFF2FBF71.toInt())
                        }
                        "pending" -> {
                            b.tvKycStatus.text = "⏳ Under Review"
                            b.tvKycStatus.setTextColor(0xFFFFA726.toInt())
                        }
                        "rejected" -> {
                            b.tvKycStatus.text = "✗ Rejected"
                            b.tvKycStatus.setTextColor(0xFFFF5C5C.toInt())
                        }
                        else -> {
                            b.tvKycStatus.text = "Not Submitted"
                            b.tvKycStatus.setTextColor(0xFF999999.toInt())
                        }
                    }

                    b.tvPanNumber.text     = info.panNumber ?: "—"
                    b.tvAadhaarNumber.text = info.aadhaarMasked ?: "—"

                    // BO Demat Number — only shown for verified accounts
                    if (info.boDematNumber != null) {
                        b.layoutDemat.visibility = View.VISIBLE
                        b.tvDematNumber.text = info.boDematNumber.chunked(4).joinToString(" ")
                    } else {
                        b.layoutDemat.visibility = View.GONE
                    }

                } else {
                    Toast.makeText(this@AccountInfoActivity, "Failed to load", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                b.progressBar.visibility = View.GONE
                Toast.makeText(this@AccountInfoActivity, e.message ?: "Error", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
