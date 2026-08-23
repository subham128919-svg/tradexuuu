package com.tradingapp.ui.wallet

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.razorpay.Checkout
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import com.tradingapp.R
import com.tradingapp.databinding.ActivityAddFundsBinding
import com.tradingapp.util.applyEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject

@AndroidEntryPoint
class AddFundsActivity : AppCompatActivity(), PaymentResultWithDataListener {

    private lateinit var b: ActivityAddFundsBinding
    private val vm: AddFundsViewModel by viewModels()

    // Set right before opening Checkout, read back in onPaymentSuccess —
    // needed because Razorpay's PaymentData doesn't always reliably
    // return order_id on every SDK version/payment method, so we keep
    // our own copy of what we asked for as a fallback.
    private var pendingOrderId: String? = null

    private val quickAmounts = listOf(500, 1000, 2000, 5000)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAddFundsBinding.inflate(layoutInflater)
        setContentView(b.root)

        runCatching { applyEdgeToEdge(topView = b.fundsHeader) }
        WindowInsetsControllerCompat(window, b.root).isAppearanceLightStatusBars = false

        b.ivClose.setOnClickListener { finish() }
        buildQuickAmountChips()

        b.btnAddMoney.setOnClickListener { startPayment() }

        vm.loadBalance()
        lifecycleScope.launch {
            vm.balance.collectLatest { bal -> b.tvCurrentBalance.text = "₹%.2f".format(bal) }
        }

        // Recommended by Razorpay for faster checkout page load — safe
        // to skip if it fails, doesn't block anything.
        runCatching { Checkout.preload(applicationContext) }
    }

    private fun buildQuickAmountChips() {
        b.quickAmountContainer.removeAllViews()
        quickAmounts.forEach { amount ->
            val chip = TextView(this).apply {
                text = "₹$amount"
                textSize = 13f
                setTextColor(Color.parseColor("#F2F4F7"))
                setBackgroundResource(R.drawable.bg_chip_off)
                setPadding(28, 18, 28, 18)
                setOnClickListener { b.etAmount.setText(amount.toString()) }
            }
            val params = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            params.marginEnd = if (amount != quickAmounts.last()) 8 else 0
            b.quickAmountContainer.addView(chip, params)
        }
    }

    private fun startPayment() {
        val amount = b.etAmount.text.toString().toDoubleOrNull()
        if (amount == null || amount <= 0) {
            showError("Enter a valid amount"); return
        }
        if (amount < 10) { showError("Minimum amount is ₹10"); return }
        if (amount > 500000) { showError("Maximum amount is ₹5,00,000"); return }

        hideError()
        b.btnAddMoney.isEnabled = false
        b.btnAddMoney.text = "Please wait…"

        lifecycleScope.launch {
            val result = vm.createOrder(amount)
            b.btnAddMoney.isEnabled = true
            b.btnAddMoney.text = "Add Money"

            result.onSuccess { order ->
                pendingOrderId = order.orderId
                openRazorpayCheckout(order.orderId, order.amount, order.currency, order.keyId)
            }.onFailure { e ->
                showError(e.message ?: "Could not start payment")
            }
        }
    }

    private fun openRazorpayCheckout(orderId: String, amountPaise: Int, currency: String, keyId: String) {
        try {
            val co = Checkout()
            co.setKeyID(keyId)
            val options = JSONObject().apply {
                put("key", keyId)
                put("order_id", orderId)
                put("amount", amountPaise)
                put("currency", currency)
                put("name", "TradeX")
                put("description", "Add funds to wallet")
                put("theme", JSONObject().put("color", "#6D5EF8"))
            }
            co.open(this, options)
        } catch (e: Exception) {
            showError("Could not open payment screen: ${e.message}")
        }
    }

    // ── PaymentResultWithDataListener ────────────────────────────────
    // Called by the Razorpay SDK once the user completes checkout. This
    // is NOT proof the wallet should be credited — that only happens
    // after verifyPayment() confirms the signature server-side (see
    // AddFundsViewModel / razorpayService.js). A "success" callback here
    // just means the payment page reported success to the SDK.
    override fun onPaymentSuccess(razorpayPaymentId: String?, paymentData: PaymentData?) {
        val orderId    = paymentData?.orderId ?: pendingOrderId
        val signature  = paymentData?.signature
        if (razorpayPaymentId == null || orderId == null || signature == null) {
            showError("Payment completed but verification data is missing — contact support with your payment ID if money was deducted.")
            return
        }
        b.btnAddMoney.isEnabled = false
        b.btnAddMoney.text = "Verifying…"
        lifecycleScope.launch {
            val result = vm.verifyPayment(orderId, razorpayPaymentId, signature)
            b.btnAddMoney.isEnabled = true
            b.btnAddMoney.text = "Add Money"
            result.onSuccess { newBalance ->
                Toast.makeText(this@AddFundsActivity, "Added successfully! New balance: ₹%.2f".format(newBalance), Toast.LENGTH_LONG).show()
                b.etAmount.setText("")
                setResult(RESULT_OK)
            }.onFailure { e ->
                showError(e.message ?: "Payment verification failed — contact support if money was deducted.")
            }
        }
    }

    override fun onPaymentError(code: Int, response: String?, paymentData: PaymentData?) {
        // Razorpay's Android SDK docs describe error code 2 as "Payment
        // Cancelled" (user backed out) — not a real failure worth
        // alarming the user with red error text. Using the raw int
        // rather than a named constant since I couldn't verify one
        // exists on this SDK version — if Razorpay does expose a
        // symbolic constant for it, prefer that instead once confirmed.
        if (code == 2) return
        showError(response ?: "Payment failed. Please try again.")
    }

    private fun showError(msg: String) {
        b.tvError.text = msg
        b.tvError.visibility = View.VISIBLE
    }
    private fun hideError() { b.tvError.visibility = View.GONE }
}
