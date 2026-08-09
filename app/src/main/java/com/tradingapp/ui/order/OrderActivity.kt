package com.tradingapp.ui.order

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.tradingapp.R
import com.tradingapp.databinding.ActivityOrderBinding
import com.tradingapp.util.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class OrderActivity : AppCompatActivity() {

    private lateinit var b: ActivityOrderBinding
    private val vm: OrderViewModel by viewModels()

    private val symbol       by lazy { intent.getStringExtra(EXTRA_SYMBOL)   ?: "" }
    private val exchange     by lazy { intent.getStringExtra(EXTRA_EXCHANGE) ?: "NSE" }
    private val name         by lazy { intent.getStringExtra(EXTRA_NAME)     ?: symbol }
    private val orderTypePrm by lazy { intent.getStringExtra("orderType")    ?: "BUY" }
    private val ltpHint      by lazy { intent.getDoubleExtra("ltp", 0.0) }
    private val defaultQty   by lazy { intent.getStringExtra("defaultQty") ?: "" }

    private var product   = "DELIVERY"
    // FIX #buy/sell toggle: starts as LIMIT, tappable to switch to MARKET
    private var priceType = "LIMIT"
    private var qty       = ""
    private var priceStr  = ""
    private var inputMode = "qty"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityOrderBinding.inflate(layoutInflater)
        setContentView(b.root)

        // FIX #10: edge-to-edge with real inset padding — header no
        // longer hides under the status bar, numpad no longer hides
        // under the nav bar.
        applyEdgeToEdge(topView = b.orderHeader, bottomView = b.numpad)
        WindowInsetsControllerCompat(window, b.root).isAppearanceLightStatusBars = false

        b.tvStockName.text = symbol
        val isBuy = orderTypePrm == "BUY"
        b.btnOrder.text = orderTypePrm
        b.btnOrder.setBackgroundColor(if (isBuy) Color.parseColor("#2FBF71") else Color.parseColor("#FF5C5C"))

        if (defaultQty.isNotEmpty()) {
            qty = defaultQty
            b.tvQtyDisplay.text = qty
        }

        b.ivClose.setOnClickListener { finish() }

        vm.loadLtp(exchange, symbol)
        vm.loadBalance()

        lifecycleScope.launch {
            vm.ltp.collectLatest { ltp ->
                val display = if (ltp > 0) ltp else ltpHint
                b.tvPriceInfo.text = "$exchange  ₹${"%.2f".format(display)}"
                if (priceStr.isEmpty() && display > 0 && priceType == "LIMIT") {
                    priceStr = "%.2f".format(display)
                    b.tvPriceDisplay.text = priceStr
                    updateRequired()
                }
            }
        }

        lifecycleScope.launch {
            vm.balance.collectLatest { bal -> b.tvBalance.text = "Balance: ₹%.2f".format(bal) }
        }

        listOf(b.tabDelivery to "DELIVERY", b.tabIntraday to "INTRADAY", b.tabMtf to "MTF").forEach { (tv, prod) ->
            tv.setOnClickListener {
                product = prod
                listOf(b.tabDelivery, b.tabIntraday, b.tabMtf).forEach {
                    it.setBackgroundResource(R.drawable.bg_chip_off)
                    it.setTextColor(Color.parseColor("#6E7681"))
                }
                tv.setBackgroundResource(R.drawable.bg_chip_on)
                tv.setTextColor(Color.parseColor("#F2F4F7"))
            }
        }

        // NEW: Market/Limit toggle — this is what the reference app has
        // and what was missing entirely before.
        b.tvPriceTypeChip.setOnClickListener {
            priceType = if (priceType == "LIMIT") "MARKET" else "LIMIT"
            applyPriceTypeUi()
        }

        b.tvQtyDisplay.setOnClickListener { inputMode = "qty"; highlightInput() }
        b.tvPriceDisplay.setOnClickListener {
            // Price field only editable in Limit mode
            if (priceType == "LIMIT") { inputMode = "price"; highlightInput() }
        }

        b.btnOrder.setOnClickListener {
            val qtyInt = qty.toIntOrNull() ?: 0
            val price  = if (priceType == "MARKET")
                (vm.ltp.value.takeIf { it > 0 } ?: ltpHint)
            else (priceStr.toDoubleOrNull() ?: 0.0)

            if (qtyInt <= 0) { Toast.makeText(this, "Enter quantity", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (price  <= 0) { Toast.makeText(this, "Price not available yet, try again", Toast.LENGTH_SHORT).show(); return@setOnClickListener }

            b.btnOrder.isEnabled = false
            lifecycleScope.launch {
                val res = vm.placeOrder(symbol, exchange, name, orderTypePrm, product, priceType, qtyInt, price)
                if (res.isSuccess) {
                    Toast.makeText(this@OrderActivity, "$orderTypePrm order placed! ✓", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this@OrderActivity, res.exceptionOrNull()?.message ?: "Order failed", Toast.LENGTH_LONG).show()
                    b.btnOrder.isEnabled = true
                }
            }
        }

        buildNumpad()
        applyPriceTypeUi()
        highlightInput()
        updateRequired()
    }

    // Switches the Price field between an editable numpad-driven value
    // (Limit) and a locked "Market" display driven by live LTP (Market).
    private fun applyPriceTypeUi() {
        b.tvPriceTypeChip.text = if (priceType == "MARKET") "Market" else "Limit"
        if (priceType == "MARKET") {
            inputMode = "qty"
            val ltp = vm.ltp.value.takeIf { it > 0 } ?: ltpHint
            b.tvPriceDisplay.text  = if (ltp > 0) "Market (₹%.2f)".format(ltp) else "Market"
            b.tvPriceDisplay.alpha = 0.6f
        } else {
            b.tvPriceDisplay.alpha = 1f
            val ltp = vm.ltp.value.takeIf { it > 0 } ?: ltpHint
            if (priceStr.isEmpty() && ltp > 0) priceStr = "%.2f".format(ltp)
            b.tvPriceDisplay.text = priceStr.ifEmpty { "0.00" }
        }
        highlightInput()
        updateRequired()
    }

    private fun highlightInput() {
        b.tvQtyDisplay.alpha   = if (inputMode == "qty")   1f else 0.55f
        if (priceType == "LIMIT") {
            b.tvPriceDisplay.alpha = if (inputMode == "price") 1f else 0.55f
        }
    }

    private fun buildNumpad() {
        val grid = b.numpad
        grid.removeAllViews()
        grid.rowCount    = 4
        grid.columnCount = 3
        val keys = listOf("1","2","3","4","5","6","7","8","9",".","0","⌫")
        keys.forEach { key ->
            val tv = TextView(this).apply {
                text     = key
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                gravity  = Gravity.CENTER
                setTextColor(Color.parseColor("#F2F4F7"))
                setBackgroundColor(Color.parseColor("#07080A"))
                val lp = GridLayout.LayoutParams().apply {
                    width      = 0
                    height     = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                    rowSpec    = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                    setMargins(1, 1, 1, 1)
                }
                layoutParams = lp
                setOnClickListener { onNumpadKey(key) }
            }
            grid.addView(tv)
        }
    }

    private fun onNumpadKey(key: String) {
        if (inputMode == "qty") {
            qty = when (key) { "⌫" -> qty.dropLast(1); "." -> qty; else -> qty + key }
            b.tvQtyDisplay.text = qty.ifEmpty { "" }
            b.tvQtyDisplay.hint = if (qty.isEmpty()) "0" else ""
        } else if (priceType == "LIMIT") {
            priceStr = when {
                key == "⌫" -> priceStr.dropLast(1)
                key == "." && priceStr.contains(".") -> priceStr
                else -> priceStr + key
            }
            b.tvPriceDisplay.text = priceStr.ifEmpty { "0.00" }
        }
        updateRequired()
    }

    private fun updateRequired() {
        val q   = qty.toIntOrNull() ?: 0
        val ltp = vm.ltp.value.takeIf { it > 0 } ?: ltpHint
        val p   = if (priceType == "MARKET") ltp else (priceStr.toDoubleOrNull() ?: ltp)
        val req = q * p
        b.tvRequired.text  = "Required: ₹%.0f".format(req)
        b.tvOrderInfo.text = if (priceType == "MARKET")
            "Order will be executed at market price"
        else
            "Order will be executed at ₹%.2f or lower price".format(p)

        if (p > 0 && ltp > 0) {
            val diff = ((p - ltp) / ltp * 100)
            b.tvPriceHint.text = if (priceType == "MARKET") "At current market price"
                                  else "%+.2f%% from market".format(diff)
        }
    }
}
