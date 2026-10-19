package com.tradingapp.ui.trade

import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tradingapp.R                      // ← added: R needs explicit import
import com.tradingapp.databinding.ScreenTradeBinding
import com.tradingapp.util.*                // ← fixed: package star import
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TradeActivity : AppCompatActivity() {
    private lateinit var b: ScreenTradeBinding
    private val vm: TradeViewModel by viewModels()
    private val symbol          by lazy { intent.getStringExtra(EXTRA_SYMBOL)   ?: "" }
    private val exchange        by lazy { intent.getStringExtra(EXTRA_EXCHANGE) ?: "NSE" }
    private val transactionType by lazy { intent.getStringExtra("transactionType") ?: "BUY" }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ScreenTradeBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.tvSymbol.text = symbol
        b.tvType.text   = transactionType
        b.tvType.setTextColor(if (transactionType == "BUY") 0xFF2FBF71.toInt() else 0xFFFF5C5C.toInt())
        b.btnOrder.text = transactionType
        b.btnOrder.setBackgroundColor(if (transactionType == "BUY") 0xFF2FBF71.toInt() else 0xFFFF5C5C.toInt())
        b.ivClose.setOnClickListener { finish() }
        val derivatives = exchange in listOf("NFO", "BFO")
        findViewById<android.view.View>(R.id.rbCnc).visibility = if (derivatives) android.view.View.GONE else android.view.View.VISIBLE
        findViewById<android.view.View>(R.id.rbNrml).visibility = if (derivatives) android.view.View.VISIBLE else android.view.View.GONE
        b.rgProduct.check(if (derivatives) R.id.rbNrml else R.id.rbCnc)
        b.etQuantity.hint = if (derivatives) "Total units (multiple of contract lot size)" else "Quantity in shares"

        b.btnOrder.setOnClickListener {
            val qty = b.etQuantity.text.toString().toIntOrNull() ?: run {
                Toast.makeText(this, "Enter quantity", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (qty <= 0) { Toast.makeText(this, "Quantity must be positive units", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            b.btnOrder.isEnabled = false
            lifecycleScope.launch {
                val product = when (b.rgProduct.checkedRadioButtonId) {
                    R.id.rbMis -> "MIS"
                    R.id.rbCnc -> "CNC"
                    else       -> "NRML"
                }
                val orderType = if (b.rgOrderType.checkedRadioButtonId == R.id.rbMarket) "MARKET" else "LIMIT"
                val price     = b.etPrice.text.toString().toDoubleOrNull() ?: 0.0

                val res = vm.placeOrder(symbol, exchange, transactionType, qty, product, orderType, price)
                b.btnOrder.isEnabled = true
                if (res is Resource.Success) {
                    Toast.makeText(this@TradeActivity, res.data, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this@TradeActivity, "Failed: ${(res as Resource.Error).message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
