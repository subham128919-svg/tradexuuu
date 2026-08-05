package com.tradingapp.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tradingapp.data.model.Quote
import com.tradingapp.databinding.ItemStockCardBinding

// Payload-based partial diffing: when only price fields change (the
// common case on every tick), only the price/change TextViews rebind.
class StockCardAdapter(private val onItemClick: (Quote) -> Unit) :
    ListAdapter<Quote, StockCardAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val b = ItemStockCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(b)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bindFull(getItem(position))

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
        } else {
            holder.bindPriceOnly(getItem(position))
        }
    }

    inner class ViewHolder(private val b: ItemStockCardBinding) : RecyclerView.ViewHolder(b.root) {
        fun bindFull(q: Quote) {
            b.tvLogo.text = q.symbol.substringAfter(":").take(2).uppercase()
            b.tvName.text = q.safeName
            bindPriceOnly(q)
            b.root.setOnClickListener { onItemClick(q) }
        }

        // FIX: symbols in the bundled universe that have never been
        // synced yet (fresh install, before first successful fetch)
        // now show "—" / "Fetching…" instead of a misleading "Rs.0.00",
        // and the card is never hidden.
        fun bindPriceOnly(q: Quote) {
            if (q.ltp <= 0.0) {
                b.tvPrice.text = "—"
                b.tvChange.text = "Fetching…"
                b.tvChange.setTextColor(Color.parseColor("#6E7681"))
                return
            }
            b.tvPrice.text = "Rs.%.2f".format(q.ltp)
            val chg = "%.2f (%.2f%%)".format(q.change, q.changePct)
            b.tvChange.text = if (q.isPositive) "+$chg" else chg
            b.tvChange.setTextColor(
                if (q.isPositive) Color.parseColor("#2FBF71") else Color.parseColor("#FF5C5C")
            )
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Quote>() {
            override fun areItemsTheSame(a: Quote, b: Quote) = a.symbol == b.symbol
            override fun areContentsTheSame(a: Quote, b: Quote) = a == b

            override fun getChangePayload(a: Quote, b: Quote): Any? {
                val aStatic = a.copy(ltp = 0.0, change = 0.0, changePct = 0.0, volume = 0, updatedAt = 0)
                val bStatic = b.copy(ltp = 0.0, change = 0.0, changePct = 0.0, volume = 0, updatedAt = 0)
                return if (aStatic == bStatic) "PRICE_ONLY" else null
            }
        }
    }
}
