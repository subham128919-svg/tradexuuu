package com.tradingapp.ui.order

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
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
    // Pre-filled from Chart (may be 0 if not loaded — we'll refresh)
    private val ltpHint      by lazy { intent.getDoubleExtra("ltp", 0.0) }

    private var product   = "DELIVERY"
    private var priceType = "LIMIT"
    private var qty       = ""
    private var priceStr  = ""
    private var inputMode = "qty"

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        b = ActivityOrderBinding.inflate(layoutInflater)
        setContentView(b.root)
        WindowInsetsControllerCompat(window, b.root).isAppearanceLightStatusBars = false

        b.tvStockName.text = symbol
        val isBuy = orderTypePrm == "BUY"
        b.btnOrder.text = orderTypePrm
        b.btnOrder.setBackgroundColor(if (isBuy) Color.parseColor("#2FBF71") else Color.parseColor("#FF5C5C"))

        b.ivClose.setOnClickListener { finish() }

        // FIX #5: Load fresh LTP from backend
        vm.loadLtp(exchange, symbol)
        vm.loadBalance()

        // Observe live LTP and pre-fill price field
        lifecycleScope.launch {
            vm.ltp.collectLatest { ltp ->
                val display = if (ltp > 0) ltp else ltpHint
                b.tvPriceInfo.text = "$exchange  ₹${"%.2f".format(display)}"
                // Only pre-fill if user hasn't typed yet
                if (priceStr.isEmpty() && display > 0) {
                    priceStr = "%.2f".format(display)
                    b.tvPriceDisplay.text = priceStr
                    updateRequired()
                }
            }
        }

        // FIX #4: Observe wallet balance
        lifecycleScope.launch {
            vm.balance.collectLatest { bal ->
                b.tvBalance.text = "Balance: ₹%.2f".format(bal)
            }
        }

        // Product tabs
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

        b.tvQtyDisplay.setOnClickListener   { inputMode = "qty";   highlightInput() }
        b.tvPriceDisplay.setOnClickListener { inputMode = "price"; highlightInput() }

        b.btnOrder.setOnClickListener {
            val qtyInt = qty.toIntOrNull() ?: 0
            val price  = priceStr.toDoubleOrNull() ?: 0.0
            if (qtyInt <= 0) { Toast.makeText(this, "Enter quantity", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (price  <= 0) { Toast.makeText(this, "Enter price",    Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            b.btnOrder.isEnabled = false
            lifecycleScope.launch {
                val res = vm.placeOrder(symbol, exchange, name, orderTypePrm, product, priceType, qtyInt, price)
                if (res.isSuccess) {
                    Toast.makeText(this@OrderActivity, "$orderTypePrm order placed! ✓", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    val msg = res.exceptionOrNull()?.message ?: "Order failed"
                    Toast.makeText(this@OrderActivity, msg, Toast.LENGTH_LONG).show()
                    b.btnOrder.isEnabled = true
                }
            }
        }

        buildNumpad()
        highlightInput()
        updateRequired()
    }

    private fun highlightInput() {
        b.tvQtyDisplay.alpha   = if (inputMode == "qty")   1f else 0.55f
        b.tvPriceDisplay.alpha = if (inputMode == "price") 1f else 0.55f
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
                    width       = 0
                    height      = 0
                    columnSpec  = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                    rowSpec     = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
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
        } else {
            priceStr = when {
                key == "⌫"                        -> priceStr.dropLast(1)
                key == "." && priceStr.contains(".") -> priceStr
                else                               -> priceStr + key
            }
            b.tvPriceDisplay.text = priceStr.ifEmpty { "0.00" }
        }
        updateRequired()
    }

    private fun updateRequired() {
        val q   = qty.toIntOrNull() ?: 0
        val p   = priceStr.toDoubleOrNull() ?: (vm.ltp.value.takeIf { it > 0 } ?: ltpHint)
        val req = q * p
        b.tvRequired.text  = "Required: ₹%.0f".format(req)
        b.tvOrderInfo.text = if (priceType == "MARKET")
            "Order will be executed at market price"
        else
            "Order will be executed at ₹%.2f or lower price".format(p)

        // FIX: Show % diff from market
        val ltp = vm.ltp.value.takeIf { it > 0 } ?: ltpHint
        if (p > 0 && ltp > 0) {
            val diff = ((p - ltp) / ltp * 100)
            b.tvPriceHint.text = "%+.2f%% from market".format(diff)
        }
    }
}
