package com.tradingapp.ui.wallet

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.razorpay.Checkout
import com.tradingapp.R
import com.tradingapp.databinding.ActivityAddFundsBinding
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

        configureScreenInsets()

        b.ivClose.setOnClickListener { finish() }
        buildQuickAmountChips()

        b.btnAddMoney.setOnClickListener { startPayment() }

        vm.loadBalance()
        lifecycleScope.launch {
            vm.balance.collectLatest { bal -> b.tvCurrentBalance.text = "₹%.2f".format(bal) }
        }

        runCatching { Checkout.preload(applicationContext) }
    }

    // Keep the header, CTA and input clear of system bars and the keyboard.
    @Suppress("DEPRECATION")
    private fun configureScreenInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, b.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }
        val headerTop = b.fundsHeader.paddingTop
        val footerBottom = b.fundsFooter.paddingBottom
        val rootStart = b.root.paddingLeft
        val rootEnd = b.root.paddingRight
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            b.fundsHeader.updatePadding(top = headerTop + bars.top)
            b.fundsFooter.updatePadding(bottom = footerBottom + maxOf(bars.bottom, keyboard.bottom))
            b.root.updatePadding(left = rootStart + bars.left, right = rootEnd + bars.right)
            insets
        }
        ViewCompat.requestApplyInsets(b.root)
    }

    private fun buildQuickAmountChips() {
        fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
        b.quickAmountContainer.removeAllViews()
        quickAmounts.forEachIndexed { index, amount ->
            val chip = TextView(this).apply {
                text = "₹$amount"
                gravity = android.view.Gravity.CENTER
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(ContextCompat.getColorStateList(this@AddFundsActivity, R.color.funds_chip_text))
                setBackgroundResource(R.drawable.funds_bg_chip)
                minHeight = dp(48)
                setPadding(dp(4), dp(12), dp(4), dp(12))
                isClickable = true
                isFocusable = true
                contentDescription = "Set amount to $amount rupees"
                setOnClickListener {
                    b.etAmount.setText(amount.toString())
                    b.etAmount.setSelection(b.etAmount.text.length)
                }
            }
            // Wrap enlarged text instead of clipping it; match the user's font scale.
            val params = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            params.marginEnd = if (index == quickAmounts.lastIndex) 0 else dp(8)
            b.quickAmountContainer.addView(chip, params)
        }
        fun updateSelection() {
            val entered = b.etAmount.text.toString().toIntOrNull()
            quickAmounts.forEachIndexed { index, amount ->
                b.quickAmountContainer.getChildAt(index).isSelected = entered == amount
            }
        }
        b.etAmount.doAfterTextChanged { updateSelection() }
        updateSelection()
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
