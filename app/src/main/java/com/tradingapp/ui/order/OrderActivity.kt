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
import androidx.lifecycle.lifecycleScope
import com.tradingapp.R
import com.tradingapp.databinding.ActivityOrderBinding
import com.tradingapp.util.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class OrderActivity : AppCompatActivity() {

    private lateinit var b: ActivityOrderBinding
    private val vm: OrderViewModel by viewModels()

    private val symbol       by lazy { intent.getStringExtra(EXTRA_SYMBOL)   ?: "" }
    private val exchange     by lazy { intent.getStringExtra(EXTRA_EXCHANGE) ?: "NSE" }
    private val name         by lazy { intent.getStringExtra(EXTRA_NAME)     ?: symbol }
    private val orderTypePrm by lazy { intent.getStringExtra("orderType")    ?: "BUY" }
    private val ltp          by lazy { intent.getDoubleExtra("ltp", 0.0) }

    private var product   = "DELIVERY"
    private var priceType = "LIMIT"
    private var qty       = ""
    private var priceStr  = ""
    private var inputMode = "qty"  // "qty" or "price"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityOrderBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.tvStockName.text = symbol
        b.tvPriceInfo.text = "$exchange ₹%.2f".format(ltp)

        val isBuy = orderTypePrm == "BUY"
        b.btnOrder.text = orderTypePrm
        b.btnOrder.setBackgroundColor(if (isBuy) Color.parseColor("#2FBF71") else Color.parseColor("#FF5C5C"))

        // Pre-fill limit price with LTP
        if (ltp > 0) {
            priceStr = "%.2f".format(ltp)
            b.tvPriceDisplay.text = priceStr
        }

        b.ivClose.setOnClickListener { finish() }

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

        // Qty / Price tap to switch input focus
        b.tvQtyDisplay.setOnClickListener { inputMode = "qty"; highlightInput() }
        b.tvPriceDisplay.setOnClickListener { inputMode = "price"; highlightInput() }

        // Update required amount when qty changes
        updateRequired()

        // Place order
        b.btnOrder.setOnClickListener {
            val qtyInt = qty.toIntOrNull() ?: 0
            val price  = priceStr.toDoubleOrNull() ?: ltp
            if (qtyInt <= 0) { Toast.makeText(this, "Enter quantity", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (price <= 0)  { Toast.makeText(this, "Enter price",    Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            b.btnOrder.isEnabled = false
            lifecycleScope.launch {
                val res = vm.placeOrder(symbol, exchange, name, orderTypePrm, product, priceType, qtyInt, price)
                if (res.isSuccess) {
                    Toast.makeText(this@OrderActivity, "$orderTypePrm order placed! ✓", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this@OrderActivity, "Failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    b.btnOrder.isEnabled = true
                }
            }
        }

        buildNumpad()
        highlightInput()
    }

    private fun highlightInput() {
        b.tvQtyDisplay.alpha   = if (inputMode == "qty")   1f else 0.5f
        b.tvPriceDisplay.alpha = if (inputMode == "price") 1f else 0.5f
    }

    private fun buildNumpad() {
        val keys = listOf("1","2","3","4","5","6","7","8","9",".","0","⌫")
        val grid = b.numpad
        grid.removeAllViews()
        keys.forEach { key ->
            val tv = TextView(this).apply {
                text      = key
                textSize  = 22f
                setTypeface(typeface, Typeface.BOLD)
                gravity   = Gravity.CENTER
                setTextColor(Color.parseColor("#F2F4F7"))
                setBackgroundColor(Color.TRANSPARENT)
                val lp = GridLayout.LayoutParams().apply {
                    width  = 0
                    height = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                    rowSpec    = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                    setMargins(2, 2, 2, 2)
                }
                layoutParams = lp
                setOnClickListener { onNumpadKey(key) }
            }
            grid.addView(tv)
        }
    }

    private fun onNumpadKey(key: String) {
        if (inputMode == "qty") {
            if (key == "⌫") qty = qty.dropLast(1)
            else if (key != ".") qty += key
            b.tvQtyDisplay.text = qty.ifEmpty { "" }
            b.tvQtyDisplay.hint = if (qty.isEmpty()) "0" else ""
        } else {
            if (key == "⌫") priceStr = priceStr.dropLast(1)
            else if (key == "." && priceStr.contains(".")) return
            else priceStr += key
            b.tvPriceDisplay.text = priceStr.ifEmpty { "0.00" }
        }
        updateRequired()
    }

    private fun updateRequired() {
        val q = qty.toIntOrNull() ?: 0
        val p = priceStr.toDoubleOrNull() ?: ltp
        val req = q * p
        b.tvRequired.text = "Required: ₹%.0f".format(req)
        b.tvOrderInfo.text = if (priceType == "MARKET")
            "Order will be executed at market price"
        else
            "Order will be executed at ₹%.2f or lower price".format(p)
    }
}
