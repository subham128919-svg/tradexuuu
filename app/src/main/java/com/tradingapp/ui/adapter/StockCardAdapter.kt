package com.tradingapp.ui.adapter

import android.graphics.Color; import android.view.*; import androidx.recyclerview.widget.DiffUtil; import androidx.recyclerview.widget.ListAdapter; import androidx.recyclerview.widget.RecyclerView
import com.tradingapp.data.model.Quote; import com.tradingapp.databinding.ItemStockCardBinding

class StockCardAdapter(private val onItemClick: (Quote) -> Unit) :
    ListAdapter<Quote, StockCardAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val b = ItemStockCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(b)
    }
    override fun onBindViewHolder(h: ViewHolder, pos: Int) = h.bind(getItem(pos))

    inner class ViewHolder(private val b: ItemStockCardBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(q: Quote) {
            b.tvLogo.text  = q.symbol.take(2).uppercase()
            b.tvName.text  = if (q.name.isNotEmpty()) q.name else q.symbol.substringAfter(":")
            b.tvPrice.text = "Rs.%.2f".format(q.ltp)
            val chg = "%.2f (%.2f%%)".format(q.change, q.changePct)
            b.tvChange.text = if (q.isPositive) "+$chg" else chg
            b.tvChange.setTextColor(if (q.isPositive) Color.parseColor("#2FBF71") else Color.parseColor("#FF5C5C"))
            b.root.setOnClickListener { onItemClick(q) }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Quote>() {
            override fun areItemsTheSame(a: Quote, b: Quote) = a.symbol == b.symbol
            override fun areContentsTheSame(a: Quote, b: Quote) = a == b
        }
    }
}
