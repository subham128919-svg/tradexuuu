package com.tradingapp.ui.wallet

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.razorpay.Checkout
import com.tradingapp.R
import com.tradingapp.databinding.ActivityAddFundsBinding
import com.tradingapp.util.applyEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AddFundsActivity : AppCompatActivity() {

    private lateinit var b: ActivityAddFundsBinding
    private val vm: AddFundsViewModel by viewModels()

    private var pendingOrderId: String? = null
    private val quickAmounts = listOf(500, 1000, 2000, 5000)

    // ── Custom payment flow callback ─────────────────────────────
    private val paymentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val paymentId = data.getStringExtra("razorpay_payment_id") ?: return@registerForActivityResult
            val ordId     = data.getStringExtra("razorpay_order_id") ?: pendingOrderId ?: return@registerForActivityResult
            val signature = data.getStringExtra("razorpay_signature") ?: return@registerForActivityResult

            b.btnAddMoney.isEnabled = false
            b.btnAddMoney.text = "Verifying…"
            lifecycleScope.launch {
                val verifyResult = vm.verifyPayment(ordId, paymentId, signature)
                b.btnAddMoney.isEnabled = true
                b.btnAddMoney.text = "Add Money"
                verifyResult.onSuccess { newBalance ->
                    Toast.makeText(this@AddFundsActivity, "Added successfully! New balance: ₹%.2f".format(newBalance), Toast.LENGTH_LONG).show()
                    b.etAmount.setText("")
                    setResult(RESULT_OK)
                }.onFailure { e ->
                    showError(e.message ?: "Payment verification failed — contact support if money was deducted.")
                }
            }
        }
    }

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

    // ── Opens custom payment method screen instead of Razorpay directly
    private fun openRazorpayCheckout(orderId: String, amountPaise: Int, currency: String, keyId: String) {
        val intent = Intent(this, PaymentMethodActivity::class.java).apply {
            putExtra("orderId", orderId)
            putExtra("amount", amountPaise)
            putExtra("currency", currency)
            putExtra("keyId", keyId)
        }
        paymentLauncher.launch(intent)
    }

    private fun showError(msg: String) {
        b.tvError.text = msg
        b.tvError.visibility = View.VISIBLE
    }
    private fun hideError() { b.tvError.visibility = View.GONE }
}
