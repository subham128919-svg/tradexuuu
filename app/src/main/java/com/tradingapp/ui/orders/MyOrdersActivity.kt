package com.tradingapp.ui.orders

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tradingapp.R
import com.tradingapp.data.api.AppOrderItem
import com.tradingapp.data.api.TradexApi
import com.tradingapp.databinding.ActivityMyOrdersBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

/**
 * Feature #4 — every order in one place: All, Pending, Executed, Cancelled.
 * Pending orders can be cancelled; a cancelled BUY refunds the held amount.
 */
@AndroidEntryPoint
class MyOrdersActivity : AppCompatActivity() {

    @Inject lateinit var api: TradexApi

    private lateinit var b: ActivityMyOrdersBinding
    private val adapter = OrderAdapter { order -> confirmCancel(order) }
    private var filter: String? = null   // null = all

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMyOrdersBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.ivBack.setOnClickListener { finish() }

        b.rvOrders.layoutManager = LinearLayoutManager(this)
        b.rvOrders.adapter = adapter

        b.btnRefresh.setOnClickListener { load() }

        val chips = listOf(
            b.chipAll to null,
            b.chipPending to "PENDING",
            b.chipExecuted to "EXECUTED",
            b.chipCancelled to "CANCELLED"
        )
        chips.forEach { (chip, value) ->
            chip.setOnClickListener {
                filter = value
                chips.forEach { (c, _) -> styleChip(c, c === chip) }
                load()
            }
        }
        styleChip(b.chipAll, true)

        load()
    }

    private fun styleChip(chip: TextView, selected: Boolean) {
        chip.setBackgroundResource(
            if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected
        )
        chip.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF5E5E70.toInt())
    }

    private fun load() = lifecycleScope.launch {
        b.progress.visibility = View.VISIBLE
        b.tvEmpty.visibility = View.GONE
        try {
            val r = api.myOrders(filter)
            b.progress.visibility = View.GONE

            if (!r.isSuccessful) { showEmpty("Could not load orders"); return@launch }

            val body  = r.body()
            val items = body?.data ?: emptyList()
            val c     = body?.counts

            b.chipAll.text       = "All" + (c?.let { " ${it.total}" } ?: "")
            b.chipPending.text   = "Pending" + (c?.let { " ${it.pending}" } ?: "")
            b.chipExecuted.text  = "Completed" + (c?.let { " ${it.executed}" } ?: "")
            b.chipCancelled.text = "Cancelled" + (c?.let { " ${it.cancelled}" } ?: "")

            adapter.submit(items)
            if (items.isEmpty()) showEmpty(
                when (filter) {
                    "PENDING"   -> "No pending orders right now."
                    "EXECUTED"  -> "No completed orders yet."
                    "CANCELLED" -> "No cancelled orders."
                    else        -> "You haven't placed any orders yet."
                }
            )
        } catch (e: Exception) {
            b.progress.visibility = View.GONE
            showEmpty(e.message ?: "Network error")
        }
    }

    private fun showEmpty(msg: String) {
        b.tvEmpty.text = msg
        b.tvEmpty.visibility = View.VISIBLE
    }

    private fun confirmCancel(order: AppOrderItem) {
        AlertDialog.Builder(this)
            .setTitle("Cancel order?")
            .setMessage(
                "${order.orderType} ${order.qty} × ${order.symbol}\n\n" +
                if (order.orderType == "BUY")
                    "The blocked amount will be returned to your wallet."
                else "This order will be withdrawn."
            )
            .setPositiveButton("Cancel order") { _, _ -> doCancel(order.id) }
            .setNegativeButton("Keep it", null)
            .show()
    }

    private fun doCancel(id: Int) = lifecycleScope.launch {
        try {
            val r = api.cancelOrder(id)
            if (r.isSuccessful) {
                Toast.makeText(this@MyOrdersActivity,
                    r.body()?.message ?: "Order cancelled", Toast.LENGTH_SHORT).show()
                load()
            } else {
                Toast.makeText(this@MyOrdersActivity,
                    "Could not cancel this order", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this@MyOrdersActivity, e.message ?: "Error", Toast.LENGTH_SHORT).show()
        }
    }
}

// ─────────────────────────────────────────────────────────────────
class OrderAdapter(
    private val onCancel: (AppOrderItem) -> Unit
) : RecyclerView.Adapter<OrderAdapter.VH>() {

    private var items: List<AppOrderItem> = emptyList()

    fun submit(list: List<AppOrderItem>) { items = list; notifyDataSetChanged() }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_app_order, parent, false)
    )

    override fun onBindViewHolder(h: VH, position: Int) = h.bind(items[position], onCancel)

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvSide    : TextView = v.findViewById(R.id.tvSide)
        private val tvSymbol  : TextView = v.findViewById(R.id.tvSymbol)
        private val tvSub     : TextView = v.findViewById(R.id.tvSub)
        private val tvAmount  : TextView = v.findViewById(R.id.tvAmount)
        private val tvStatus  : TextView = v.findViewById(R.id.tvStatus)
        private val tvWhen    : TextView = v.findViewById(R.id.tvWhen)
        private val btnCancel : TextView = v.findViewById(R.id.btnCancel)

        fun bind(o: AppOrderItem, onCancel: (AppOrderItem) -> Unit) {
            val isBuy = o.orderType == "BUY"
            tvSide.text = if (isBuy) "B" else "S"
            tvSide.setBackgroundResource(
                if (isBuy) R.drawable.bg_badge_green else R.drawable.bg_badge_red)

            tvSymbol.text = o.symbol
            tvSub.text = buildString {
                append(o.orderType ?: "")
                append(" · ").append(o.qty).append(" qty")
                o.product?.let { append(" · ").append(it) }
                o.exchange?.let { append(" · ").append(it) }
            }

            val unit = o.avgPrice ?: o.price
            tvAmount.text = "₹%,.2f".format(unit * o.qty)

            tvStatus.text = when (o.status) {
                "EXECUTED"  -> "COMPLETED"
                "PENDING"   -> "PENDING"
                "CANCELLED" -> "CANCELLED"
                else        -> o.status ?: "—"
            }
            tvStatus.setBackgroundResource(
                when (o.status) {
                    "EXECUTED"  -> R.drawable.bg_badge_green
                    "PENDING"   -> R.drawable.bg_badge_amber
                    else        -> R.drawable.bg_badge_grey
                }
            )

            tvWhen.text = formatWhen(o.placedAt)

            if (o.status == "PENDING") {
                btnCancel.visibility = View.VISIBLE
                btnCancel.setOnClickListener { onCancel(o) }
            } else {
                btnCancel.visibility = View.GONE
                btnCancel.setOnClickListener(null)
            }
        }

        private fun formatWhen(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            val inFormats = listOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd HH:mm:ss"
            )
            val out = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            for (f in inFormats) {
                try {
                    val d = SimpleDateFormat(f, Locale.US).parse(raw) ?: continue
                    return out.format(d)
                } catch (_: Exception) { }
            }
            return raw.replace("T", " ").take(16)
        }
    }
}
