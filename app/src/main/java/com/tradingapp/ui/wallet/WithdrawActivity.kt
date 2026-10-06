package com.tradingapp.ui.wallet

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tradingapp.R
import com.tradingapp.data.api.ApiService
import com.tradingapp.data.api.PayoutMethod
import com.tradingapp.data.api.TradexApi
import com.tradingapp.data.api.WithdrawConfig
import com.tradingapp.databinding.ActivityWithdrawBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Feature #7 — Withdraw funds.
 *
 * Flow: add a UPI ID or bank account → pick it → enter an amount →
 * the request goes to the admin, who approves or rejects it at
 * /admin/manage.html → Withdrawals.
 *
 * The amount is held (debited) the moment the request is created, so the
 * same balance cannot be withdrawn twice. A rejection refunds it.
 */
@AndroidEntryPoint
class WithdrawActivity : AppCompatActivity() {

    @Inject lateinit var api: TradexApi
    @Inject lateinit var walletApi: ApiService

    private lateinit var b: ActivityWithdrawBinding

    private var methods: List<PayoutMethod> = emptyList()
    private var selected: PayoutMethod? = null
    private var config: WithdrawConfig? = null
    private var balance: Double = 0.0

    private val addMethodLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (it.resultCode == RESULT_OK) loadMethods() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityWithdrawBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.ivBack.setOnClickListener { finish() }
        b.btnAddMethod.setOnClickListener {
            addMethodLauncher.launch(Intent(this, AddPayoutMethodActivity::class.java))
        }
        b.btnWithdrawAll.setOnClickListener {
            val max = minOf(balance, config?.max ?: balance)
            b.etAmount.setText(max.toInt().toString())
        }
        b.btnSubmit.setOnClickListener { submit() }

        loadAll()
    }

    private fun loadAll() {
        loadConfigAndBalance()
        loadMethods()
        loadHistory()
    }

    private fun loadConfigAndBalance() = lifecycleScope.launch {
        try {
            val c = api.withdrawConfig()
            if (c.isSuccessful) {
                config = c.body()
                val cfg = config!!
                b.tvLimits.text = "Min ₹%,.0f  ·  Max ₹%,.0f".format(cfg.min, cfg.max)
                if (!cfg.note.isNullOrBlank()) {
                    b.tvNote.text = cfg.note; b.tvNote.visibility = View.VISIBLE
                } else b.tvNote.visibility = View.GONE

                if (!cfg.enabled) {
                    b.btnSubmit.isEnabled = false
                    b.btnSubmit.setBackgroundResource(R.drawable.bg_btn_disabled)
                    b.btnSubmit.text = "Withdrawals are paused"
                }
            }
        } catch (_: Exception) { }

        try {
            val w = walletApi.getWallet()
            if (w.isSuccessful) {
                balance = w.body()?.balance ?: 0.0
                b.tvBalance.text = "₹%,.2f".format(balance)
            }
        } catch (_: Exception) { }
    }

    private fun loadMethods() = lifecycleScope.launch {
        try {
            val r = api.payoutMethods()
            if (!r.isSuccessful) return@launch
            methods = r.body()?.data ?: emptyList()

            b.methodContainer.removeAllViews()
            if (methods.isEmpty()) {
                b.tvNoMethod.visibility = View.VISIBLE
                selected = null
                return@launch
            }
            b.tvNoMethod.visibility = View.GONE
            if (selected == null || methods.none { it.id == selected!!.id })
                selected = methods.firstOrNull { it.isDefault } ?: methods.first()

            methods.forEach { m -> b.methodContainer.addView(buildMethodRow(m)) }
        } catch (_: Exception) { }
    }

    private fun buildMethodRow(m: PayoutMethod): View {
        val row = layoutInflater.inflate(R.layout.item_payout_method, b.methodContainer, false)
        val radio = row.findViewById<TextView>(R.id.tvRadio)
        val title = row.findViewById<TextView>(R.id.tvMethodTitle)
        val sub   = row.findViewById<TextView>(R.id.tvMethodSub)
        val del   = row.findViewById<TextView>(R.id.btnDelete)

        val isUpi = m.type == "upi"
        title.text = if (isUpi) "UPI" else (m.bankName ?: "Bank account")
        sub.text   = if (isUpi) (m.upiId ?: "")
                     else listOfNotNull(m.accountHolder, m.accountMasked, m.ifsc)
                             .joinToString("  ·  ")

        val isSel = selected?.id == m.id
        radio.text = if (isSel) "●" else "○"
        radio.setTextColor(if (isSel) 0xFF6D5EF8.toInt() else 0xFFBFC2CF.toInt())

        row.setOnClickListener { selected = m; loadMethodsRefreshSelection() }
        del.setOnClickListener { confirmDelete(m) }
        return row
    }

    private fun loadMethodsRefreshSelection() {
        b.methodContainer.removeAllViews()
        methods.forEach { m -> b.methodContainer.addView(buildMethodRow(m)) }
    }

    private fun confirmDelete(m: PayoutMethod) {
        AlertDialog.Builder(this)
            .setTitle("Remove payout method?")
            .setMessage(m.label ?: "")
            .setPositiveButton("Remove") { _, _ ->
                lifecycleScope.launch {
                    try {
                        api.deletePayoutMethod(m.id)
                        if (selected?.id == m.id) selected = null
                        loadMethods()
                    } catch (_: Exception) { }
                }
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun loadHistory() = lifecycleScope.launch {
        try {
            val r = api.myWithdrawals()
            if (!r.isSuccessful) return@launch
            val items = r.body()?.data ?: emptyList()

            b.historyContainer.removeAllViews()
            if (items.isEmpty()) { b.tvNoHistory.visibility = View.VISIBLE; return@launch }
            b.tvNoHistory.visibility = View.GONE

            items.forEach { w ->
                val row = layoutInflater.inflate(R.layout.item_withdrawal, b.historyContainer, false)
                row.findViewById<TextView>(R.id.tvWdAmount).text = "₹%,.2f".format(w.amount)
                row.findViewById<TextView>(R.id.tvWdMethod).text =
                    (w.method ?: w.methodType ?: "") +
                    (w.createdAt?.let { "\n" + it.replace("T", " ").take(16) } ?: "")

                val badge = row.findViewById<TextView>(R.id.tvWdStatus)
                badge.text = (w.status ?: "").uppercase()
                badge.setBackgroundResource(
                    when (w.status) {
                        "approved" -> R.drawable.bg_badge_green
                        "rejected" -> R.drawable.bg_badge_red
                        else       -> R.drawable.bg_badge_amber
                    }
                )
                val remarks = row.findViewById<TextView>(R.id.tvWdRemarks)
                val note = listOfNotNull(w.referenceNo?.let { "Ref: $it" }, w.remarks)
                    .joinToString(" · ")
                if (note.isBlank()) remarks.visibility = View.GONE
                else { remarks.text = note; remarks.visibility = View.VISIBLE }

                b.historyContainer.addView(row)
            }
        } catch (_: Exception) { }
    }

    private fun submit() {
        val amount = b.etAmount.text.toString().trim().toDoubleOrNull()
        val method = selected

        if (method == null) { err("Add a UPI ID or bank account first"); return }
        if (amount == null || amount <= 0) { err("Enter a valid amount"); return }

        config?.let { c ->
            if (!c.enabled)   { err("Withdrawals are temporarily disabled"); return }
            if (amount < c.min) { err("Minimum withdrawal is ₹%,.0f".format(c.min)); return }
            if (amount > c.max) { err("Maximum withdrawal is ₹%,.0f".format(c.max)); return }
        }
        if (amount > balance) { err("You only have ₹%,.2f available".format(balance)); return }

        AlertDialog.Builder(this)
            .setTitle("Confirm withdrawal")
            .setMessage(
                "₹%,.2f will be sent to:\n%s\n\nThe amount is held immediately and paid out after admin approval."
                    .format(amount, method.label ?: "")
            )
            .setPositiveButton("Request withdrawal") { _, _ -> doSubmit(amount, method.id) }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun doSubmit(amount: Double, methodId: Int) = lifecycleScope.launch {
        b.btnSubmit.isEnabled = false
        b.btnSubmit.text = "Submitting…"
        hideErr()
        try {
            val r = api.requestWithdrawal(mapOf("amount" to amount, "methodId" to methodId))
            b.btnSubmit.isEnabled = true
            b.btnSubmit.text = "Request withdrawal"

            if (r.isSuccessful && r.body()?.ok == true) {
                val body = r.body()!!
                balance = body.newBalance
                b.tvBalance.text = "₹%,.2f".format(balance)
                b.etAmount.setText("")
                Toast.makeText(this@WithdrawActivity,
                    body.message ?: "Withdrawal requested", Toast.LENGTH_LONG).show()
                loadHistory()
                setResult(RESULT_OK)
            } else {
                err(parseError(r.errorBody()?.string()))
            }
        } catch (e: Exception) {
            b.btnSubmit.isEnabled = true
            b.btnSubmit.text = "Request withdrawal"
            err(e.message ?: "Network error")
        }
    }

    private fun parseError(raw: String?): String {
        if (raw.isNullOrBlank()) return "Request failed"
        return Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
            ?: "Request failed"
    }

    private fun err(msg: String) { b.tvError.text = msg; b.tvError.visibility = View.VISIBLE }
    private fun hideErr() { b.tvError.visibility = View.GONE }
}
