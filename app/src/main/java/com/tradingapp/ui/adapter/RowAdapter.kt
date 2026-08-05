package com.tradingapp.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tradingapp.R
import com.tradingapp.databinding.ItemRowBinding

// Generic row shown in Holdings, Positions, Orders, Watchlist
data class RowItem(
    val logo: String, val name: String, val sub: String,
    val right: String, val rightSub: String, val tag: String = "",
    val symbol: String = "", val exchange: String = "NSE"
)

class RowAdapter(private val onItemClick: (RowItem) -> Unit) :
    ListAdapter<RowItem, RowAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, pos: Int) = holder.bindFull(getItem(pos))

    override fun onBindViewHolder(holder: ViewHolder, pos: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, pos)
        } else {
            holder.bindPriceOnly(getItem(pos))
        }
    }

    inner class ViewHolder(private val b: ItemRowBinding) : RecyclerView.ViewHolder(b.root) {
        fun bindFull(item: RowItem) {
            b.tvLogo.text = item.logo
            b.tvName.text = item.name
            b.tvSub.text  = item.sub
            bindPriceOnly(item)
            if (item.tag.isNotEmpty()) {
                b.tvTag.text = item.tag
                b.tvTag.visibility = View.VISIBLE
                val isBuy = item.tag == "BUY"
                b.tvTag.setTextColor(if (isBuy) Color.parseColor("#2FBF71") else Color.parseColor("#FF5C5C"))
                b.tvTag.setBackgroundResource(if (isBuy) R.drawable.bg_tag_buy else R.drawable.bg_tag_sell)
            } else {
                b.tvTag.visibility = View.GONE
            }
            b.root.setOnClickListener { onItemClick(item) }
        }

        fun bindPriceOnly(item: RowItem) {
            b.tvRight.text = item.right
            val rs = item.rightSub
            b.tvRightSub.text = rs
            val color = when {
                rs.startsWith("+") -> Color.parseColor("#2FBF71")
                rs.startsWith("-") -> Color.parseColor("#FF5C5C")
                else -> Color.parseColor("#8A93A0")
            }
            b.tvRightSub.setTextColor(color)
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<RowItem>() {
            override fun areItemsTheSame(a: RowItem, b: RowItem) = a.symbol == b.symbol && a.name == b.name
            override fun areContentsTheSame(a: RowItem, b: RowItem) = a == b

            override fun getChangePayload(a: RowItem, b: RowItem): Any? {
                val aStatic = a.copy(right = "", rightSub = "")
                val bStatic = b.copy(right = "", rightSub = "")
                return if (aStatic == bStatic) "PRICE_ONLY" else null
            }
        }
    }
}
