package com.tradingapp.ui.wallet

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.razorpay.Checkout
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import com.tradingapp.databinding.ActivityPaymentMethodBinding
import org.json.JSONObject

/**
 * Custom payment method selection screen.
 * 
 * Instead of opening Razorpay's default checkout page,
 * this shows a branded screen with UPI / Card / Net Banking options.
 * After the user selects a method, we open Razorpay Checkout with 
 * that specific method pre-selected, so it looks seamless.
 * 
 * Receives from AddFundsActivity:
 *   - orderId, amount (paise), currency, keyId
 * Returns:
 *   - razorpay_payment_id, razorpay_order_id, razorpay_signature
 */
class PaymentMethodActivity : AppCompatActivity(), PaymentResultWithDataListener {

    private lateinit var b: ActivityPaymentMethodBinding

    private val orderId  by lazy { intent.getStringExtra("orderId") ?: "" }
    private val amount   by lazy { intent.getIntExtra("amount", 0) }
    private val currency by lazy { intent.getStringExtra("currency") ?: "INR" }
    private val keyId    by lazy { intent.getStringExtra("keyId") ?: "" }

    private var selectedMethod = "" // "upi", "card", "netbanking"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPaymentMethodBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Display amount
        val amountRs = amount / 100.0
        b.tvAmount.text = "₹${"%,.0f".format(amountRs)}"

        b.ivBack.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        // Payment method selection
        b.cardUpi.setOnClickListener        { selectMethod("upi") }
        b.cardCreditDebit.setOnClickListener { selectMethod("card") }
        b.cardNetBanking.setOnClickListener  { selectMethod("netbanking") }

        b.btnContinue.setOnClickListener {
            if (selectedMethod.isEmpty()) {
                Toast.makeText(this, "Please select a payment method", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            openRazorpayWithMethod()
        }

        // Preload Razorpay
        runCatching { Checkout.preload(applicationContext) }
    }

    private fun selectMethod(method: String) {
        selectedMethod = method
        // Reset all cards
        b.cardUpi.setCardBackgroundColor(Color.WHITE)
        b.cardCreditDebit.setCardBackgroundColor(Color.WHITE)
        b.cardNetBanking.setCardBackgroundColor(Color.WHITE)
        b.radioUpi.isChecked = false
        b.radioCreditDebit.isChecked = false
        b.radioNetBanking.isChecked = false

        // Highlight selected
        val selectedColor = Color.parseColor("#F0EDFF")
        when (method) {
            "upi" -> {
                b.cardUpi.setCardBackgroundColor(selectedColor)
                b.radioUpi.isChecked = true
            }
            "card" -> {
                b.cardCreditDebit.setCardBackgroundColor(selectedColor)
                b.radioCreditDebit.isChecked = true
            }
            "netbanking" -> {
                b.cardNetBanking.setCardBackgroundColor(selectedColor)
                b.radioNetBanking.isChecked = true
            }
        }

        b.btnContinue.isEnabled = true
        b.btnContinue.alpha = 1.0f
    }

    private fun openRazorpayWithMethod() {
        try {
            val co = Checkout()
            co.setKeyID(keyId)

            val options = JSONObject().apply {
                put("key", keyId)
                put("order_id", orderId)
                put("amount", amount)
                put("currency", currency)
                put("name", "TradeX")
                put("description", "Add funds to wallet")
                put("theme", JSONObject().put("color", "#6D5EF8"))

                // Pre-select the payment method
                put("method", JSONObject().apply {
                    // Disable all methods first, enable only selected
                    put("upi", selectedMethod == "upi")
                    put("card", selectedMethod == "card")
                    put("netbanking", selectedMethod == "netbanking")
                    put("wallet", false)
                    put("paylater", false)
                    put("emi", false)
                })

                // Pre-fill phone if available
                val prefs = getSharedPreferences("tradingapp_prefs", MODE_PRIVATE)
                val phone = prefs.getString("user_phone", null)
                if (!phone.isNullOrEmpty()) {
                    put("prefill", JSONObject().put("contact", phone))
                }
            }

            co.open(this, options)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open payment: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Razorpay Callbacks ───────────────────────────────────────
    override fun onPaymentSuccess(razorpayPaymentId: String?, paymentData: PaymentData?) {
        val resultIntent = Intent().apply {
            putExtra("razorpay_payment_id", razorpayPaymentId)
            putExtra("razorpay_order_id", paymentData?.orderId ?: orderId)
            putExtra("razorpay_signature", paymentData?.signature)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    override fun onPaymentError(code: Int, response: String?, paymentData: PaymentData?) {
        if (code == 2) {
            // User cancelled — go back to method selection, don't close
            return
        }
        Toast.makeText(this, response ?: "Payment failed", Toast.LENGTH_LONG).show()
    }
}
