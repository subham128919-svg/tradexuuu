package com.tradingapp.ui.wallet

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tradingapp.R
import com.tradingapp.data.api.TradexApi
import com.tradingapp.databinding.ActivityAddPayoutMethodBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Feature #7 — add the UPI ID or bank account that withdrawals are paid to. */
@AndroidEntryPoint
class AddPayoutMethodActivity : AppCompatActivity() {

    @Inject lateinit var api: TradexApi
    private lateinit var b: ActivityAddPayoutMethodBinding
    private var isUpi = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAddPayoutMethodBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.ivBack.setOnClickListener { finish() }
        b.tabUpi.setOnClickListener  { switchTab(true) }
        b.tabBank.setOnClickListener { switchTab(false) }
        b.btnSave.setOnClickListener { save() }

        switchTab(true)
    }

    private fun switchTab(upi: Boolean) {
        isUpi = upi
        b.tabUpi.setBackgroundResource(
            if (upi) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
        b.tabUpi.setTextColor(if (upi) 0xFFFFFFFF.toInt() else 0xFF5E5E70.toInt())
        b.tabBank.setBackgroundResource(
            if (!upi) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
        b.tabBank.setTextColor(if (!upi) 0xFFFFFFFF.toInt() else 0xFF5E5E70.toInt())

        b.groupUpi.visibility  = if (upi) View.VISIBLE else View.GONE
        b.groupBank.visibility = if (upi) View.GONE else View.VISIBLE
        hideErr()
    }

    private fun save() {
        val body: Map<String, Any>

        if (isUpi) {
            val upi = b.etUpi.text.toString().trim()
            if (!Regex("^[\\w.\\-]{2,60}@[a-zA-Z]{2,20}$").matches(upi)) {
                err("Enter a valid UPI ID, for example name@okaxis"); return
            }
            body = mapOf("type" to "upi", "upiId" to upi)
        } else {
            val holder = b.etHolder.text.toString().trim()
            val acc    = b.etAccount.text.toString().trim().replace(" ", "")
            val accRe  = b.etAccountRepeat.text.toString().trim().replace(" ", "")
            val ifsc   = b.etIfsc.text.toString().trim().uppercase()
            val bank   = b.etBank.text.toString().trim()

            if (holder.length < 3)             { err("Enter the account holder's full name"); return }
            if (!Regex("^\\d{6,20}$").matches(acc)) { err("Enter a valid account number"); return }
            if (acc != accRe)                  { err("The account numbers do not match"); return }
            if (!Regex("^[A-Z]{4}0[A-Z0-9]{6}$").matches(ifsc)) {
                err("Enter a valid IFSC code, for example HDFC0001234"); return
            }
            body = mapOf(
                "type" to "bank", "accountHolder" to holder,
                "accountNumber" to acc, "ifsc" to ifsc, "bankName" to bank
            )
        }

        lifecycleScope.launch {
            b.btnSave.isEnabled = false
            b.btnSave.text = "Saving…"
            hideErr()
            try {
                val r = api.addPayoutMethod(body)
                b.btnSave.isEnabled = true
                b.btnSave.text = "Save payout method"

                if (r.isSuccessful && r.body()?.ok == true) {
                    Toast.makeText(this@AddPayoutMethodActivity,
                        r.body()?.message ?: "Saved", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                } else {
                    val raw = r.errorBody()?.string()
                    err(Regex("\"error\"\\s*:\\s*\"([^\"]+)\"")
                        .find(raw ?: "")?.groupValues?.get(1) ?: "Could not save")
                }
            } catch (e: Exception) {
                b.btnSave.isEnabled = true
                b.btnSave.text = "Save payout method"
                err(e.message ?: "Network error")
            }
        }
    }

    private fun err(m: String) { b.tvError.text = m; b.tvError.visibility = View.VISIBLE }
    private fun hideErr() { b.tvError.visibility = View.GONE }
}
