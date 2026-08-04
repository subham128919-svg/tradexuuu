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

        b.btnOrder.setOnClickListener {
            val qty = b.etQuantity.text.toString().toIntOrNull() ?: run {
                Toast.makeText(this, "Enter quantity", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val product = when (b.rgProduct.checkedRadioButtonId) {
                    R.id.rbMis -> "MIS"
                    R.id.rbCnc -> "CNC"
                    else       -> "NRML"
                }
                val orderType = if (b.rgOrderType.checkedRadioButtonId == R.id.rbMarket) "MARKET" else "LIMIT"
                val price     = b.etPrice.text.toString().toDoubleOrNull() ?: 0.0

                val res = vm.placeOrder(symbol, exchange, transactionType, qty, product, orderType, price)
                if (res is Resource.Success) {
                    Toast.makeText(this@TradeActivity, "Order placed! ID: ${res.data}", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this@TradeActivity, "Failed: ${(res as Resource.Error).message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
